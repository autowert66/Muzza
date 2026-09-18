package com.maloy.muzza.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.maloy.innertube.YouTube
import com.maloy.muzza.constants.AudioQuality
import com.maloy.muzza.constants.AudioQualityKey
import com.maloy.muzza.db.MusicDatabase
import com.maloy.muzza.db.SQLITE_MAX_VARIABLES
import com.maloy.muzza.db.entities.FormatEntity
import com.maloy.muzza.db.entities.Song
import com.maloy.muzza.db.entities.SongEntity
import com.maloy.muzza.di.DownloadCache
import com.maloy.muzza.di.PlayerCache
import com.maloy.muzza.models.MediaMetadata
import com.maloy.muzza.utils.CoverStore
import com.maloy.muzza.utils.YTPlayerUtils
import com.maloy.muzza.utils.canonicalCoverUrl
import com.maloy.muzza.utils.enumPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DownloadUtil @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
     databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val songUrlCache = HashMap<String, Pair<String, Long>>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Downloads write straight into the download cache through the DownloadManager, so this
    // upstream must not go through the player (stream) cache: doing so made every downloaded
    // song also land in the player cache, duplicating the bytes and inflating the song cache.
    private val dataSourceFactory = ResolvingDataSource.Factory(
        OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .proxy(YouTube.proxy)
                .apply {
                    // Only install the authenticator when credentials exist: OkHttp invokes it
                    // on any 407, and force-unwrapping a null proxyAuth() crashed the process.
                    YouTube.proxyAuth?.let { auth ->
                        proxyAuthenticator { _, response ->
                            response.request.newBuilder()
                                .header("Proxy-Authorization", auth)
                                .build()
                        }
                    }
                }
                .build(),
        ),
    ) { dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")

        songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
            return@Factory dataSpec.withUri(it.first.toUri())
        }

        val playbackData = runBlocking(Dispatchers.IO) {
            YTPlayerUtils.playerResponseForPlayback(
                mediaId,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
            )
        }.getOrThrow()
        val format = playbackData.format



        database.query {
            upsert(
                FormatEntity(
                    id = mediaId,
                    itag = format.itag,
                    mimeType = format.mimeType.split(";")[0],
                    codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                    bitrate = format.bitrate,
                    sampleRate = format.audioSampleRate,
                    contentLength = format.contentLength!!,
                    loudnessDb = playbackData.audioConfig?.loudnessDb,
                    playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
                    perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                ),
            )
        }

        val streamUrl = playbackData.streamUrl.let {
            // Specify range to avoid YouTube's throttling
            "${it}&range=0-${format.contentLength ?: 10000000}"
        }

        // Absolute expiry instant (was storing the raw duration, so the cache-hit check above never fired).
        songUrlCache[mediaId] = streamUrl to
            (System.currentTimeMillis() + playbackData.streamExpiresInSeconds * 1000L)
        dataSpec.withUri(streamUrl.toUri())
    }
    val downloadNotificationHelper = DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)
    val downloadManager: DownloadManager = DownloadManager(context, databaseProvider, downloadCache, dataSourceFactory, Executor(Runnable::run)).apply {
        maxParallelDownloads = 3
        addListener(
            ExoDownloadService.TerminalStateNotificationHelper(
                context = context,
                notificationHelper = downloadNotificationHelper,
                nextNotificationId = ExoDownloadService.NOTIFICATION_ID + 1
            )
        )
    }
    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Completed download ids, collapsed so that DownloadManager progress ticks don't re-trigger
    // database queries. This is the single cheap source of truth for "is downloaded".
    val downloadedIds: StateFlow<Set<String>> = downloads
        .map { map -> map.filterValues { it.state == Download.STATE_COMPLETED }.keys }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    // Shared list of downloaded songs. Queries only the completed ids (chunked) instead of
    // materializing the entire song table with its relations, and is cached across screens.
    // `null` means "not loaded yet" so callers can show a loading state instead of "empty".
    val downloadedSongs: StateFlow<List<Song>?> = combine(downloadedIds, downloads) { ids, map ->
        ids to map
    }
        .flatMapLatest { (ids, map) ->
            songsByIdsFlowChunked(ids).map { songs ->
                songs.sortedBy { map[it.id]?.updateTimeMs ?: 0L }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    fun getDownload(songId: String?): Flow<Download?> = downloads.map { it[songId] }

    fun download(songs: List<MediaMetadata>) {
        songs.forEach { song -> download(song) }
    }
    fun download(song: MediaMetadata) {
        downloadSong(song.id, song.title, song.thumbnailUrl)
    }
    fun download(song: SongEntity) {
        downloadSong(song.id, song.title, song.thumbnailUrl)
    }
    fun download(song: Song) {
        download(song.song)
    }
    private fun downloadSong(id: String, title: String, thumbnailUrl: String?) {
        val downloadRequest = DownloadRequest.Builder(id, id.toUri())
            .setCustomCacheKey(id)
            .setData(title.toByteArray())
            .build()
        DownloadService.sendAddDownload(
            context,
            ExoDownloadService::class.java,
            downloadRequest,
            false)
        scope.launch { CoverStore.ensure(context, thumbnailUrl) }
    }

    private suspend fun getSongsByIdsChunked(ids: Collection<String>): List<Song> =
        ids.chunked(SQLITE_MAX_VARIABLES).flatMap { database.getSongsByIds(it) }

    private fun songsByIdsFlowChunked(ids: Collection<String>): Flow<List<Song>> {
        if (ids.isEmpty()) return flowOf(emptyList())
        return combine(ids.chunked(SQLITE_MAX_VARIABLES).map { database.songsByIdsFlow(it) }) { chunks ->
            chunks.flatMap { it }
        }
    }

    private suspend fun ensureCoverFor(id: String) {
        val song = database.getSongsByIds(listOf(id)).firstOrNull() ?: return
        if (song.song.isLocal) return
        CoverStore.ensure(context, song.song.thumbnailUrl)
    }

    private suspend fun deleteCoverFor(id: String) {
        val song = database.getSongsByIds(listOf(id)).firstOrNull() ?: return
        val thumbnailUrl = song.song.thumbnailUrl ?: return
        val key = canonicalCoverUrl(thumbnailUrl)
        val remainingIds = downloads.value.keys - id
        val stillUsed = remainingIds.isNotEmpty() &&
            getSongsByIdsChunked(remainingIds)
                .any { it.song.thumbnailUrl?.let(::canonicalCoverUrl) == key }
        if (!stillUsed) CoverStore.delete(context, thumbnailUrl)
    }

    init {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result
        // A song that was streamed before it was downloaded keeps a redundant copy in the stream
        // cache. The download is authoritative once complete, so drop the copy instead of letting
        // it keep counting toward the song cache.
        val completedIds = result.filterValues { it.state == Download.STATE_COMPLETED }.keys
        cleanupScope.launch {
            playerCache.keys.filter { it in completedIds }.forEach(::dropRedundantStreamCache)
        }
        downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    downloads.update { map ->
                        map.toMutableMap().apply {
                            set(download.request.id, download)
                        }
                    }
                    if (download.state == Download.STATE_COMPLETED) {
                        cleanupScope.launch { dropRedundantStreamCache(download.request.id) }
                        scope.launch { ensureCoverFor(download.request.id) }
                    }
                }

                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                    downloads.update { it - download.request.id }
                    scope.launch { deleteCoverFor(download.request.id) }
                }
            }
        )
        scope.launch {
            val completedIds = result.values
                .filter { it.state == Download.STATE_COMPLETED }
                .map { it.request.id }
            getSongsByIdsChunked(completedIds)
                .filterNot { it.song.isLocal }
                .forEach { CoverStore.ensure(context, it.song.thumbnailUrl) }
        }
    }

    private fun dropRedundantStreamCache(mediaId: String) {
        runCatching { playerCache.removeResource(mediaId) }
    }
}
