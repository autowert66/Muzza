package com.maloy.muzza.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maloy.muzza.db.MusicDatabase
import com.maloy.muzza.db.SQLITE_MAX_VARIABLES
import com.maloy.muzza.db.entities.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.maloy.muzza.di.PlayerCache
import com.maloy.muzza.di.DownloadCache
import androidx.media3.datasource.cache.SimpleCache
import com.maloy.muzza.constants.SongSortDescendingKey
import com.maloy.muzza.constants.SongSortType
import com.maloy.muzza.constants.SongSortTypeKey
import com.maloy.muzza.extensions.reversed
import com.maloy.muzza.extensions.toEnum
import com.maloy.muzza.utils.CacheWatcher
import com.maloy.muzza.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime

@HiltViewModel
class CachePlaylistViewModel @Inject constructor(
    private val database: MusicDatabase,
    private val cacheWatcher: CacheWatcher,
    @PlayerCache private val playerCache: SimpleCache,
    @DownloadCache private val downloadCache: SimpleCache,
    @ApplicationContext context: Context
) : ViewModel() {
    val cachedSongs: StateFlow<List<Song>?> = combine(
        context.dataStore.data
            .map { preferences ->
                Pair(
                    preferences[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE),
                    (preferences[SongSortDescendingKey] ?: true)
                )
            }
            .distinctUntilChanged(),
        cacheWatcher.ticks
    ) { sort, _ -> sort }
        .map { (sortType, descending) ->
            computeCachedSongs().let { songs ->
                when (sortType) {
                    SongSortType.CREATE_DATE ->
                        songs.sortedBy { descending }

                    SongSortType.NAME ->
                        songs.sortedBy { it.song.title }

                    SongSortType.ARTIST ->
                        songs.sortedBy { song ->
                            song.artists.joinToString(separator = "") { it.name }
                        }

                    SongSortType.PLAY_TIME ->
                        songs.sortedBy { it.song.totalPlayTime }
                }.reversed(!descending)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    private suspend fun computeCachedSongs(): List<Song> = withContext(Dispatchers.IO) {
        val cachedIds = playerCache.keys.toSet()
        val downloadedIds = downloadCache.keys.toSet()
        val pureCacheIds = cachedIds.subtract(downloadedIds)

        val songs = if (pureCacheIds.isNotEmpty()) {
            pureCacheIds.toList().chunked(SQLITE_MAX_VARIABLES).flatMap { database.getSongsByIds(it) }
        } else {
            emptyList()
        }

        val completeSongs = songs.filter {
            val contentLength = it.format?.contentLength
            contentLength != null && playerCache.isCached(it.song.id, 0, contentLength)
        }

        val now = LocalDateTime.now()
        val withDate = completeSongs.map { song ->
            if (song.song.dateDownload == null) {
                song.copy(song = song.song.copy(dateDownload = now))
            } else {
                song
            }
        }
        if (completeSongs.any { it.song.dateDownload == null }) {
            database.query {
                completeSongs.forEach {
                    if (it.song.dateDownload == null) update(it.song.copy(dateDownload = now))
                }
            }
        }
        withDate.sortedByDescending { it.song.dateDownload }
    }

    fun removeSongFromCache(songId: String) {
        playerCache.removeResource(songId)
    }
}