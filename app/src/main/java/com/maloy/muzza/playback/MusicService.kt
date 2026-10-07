package com.maloy.muzza.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.maloy.innertube.YouTube
import com.maloy.innertube.models.SongItem
import com.maloy.innertube.models.WatchEndpoint
import com.maloy.muzza.MainActivity
import com.maloy.muzza.R
import com.maloy.muzza.constants.AddingPlayedSongsToYTMHistoryKey
import com.maloy.muzza.constants.AudioNormalizationKey
import com.maloy.muzza.constants.AudioOffload
import com.maloy.muzza.constants.AudioQuality
import com.maloy.muzza.constants.AudioQualityKey
import com.maloy.muzza.constants.AutoLoadMoreKey
import com.maloy.muzza.constants.AutoPlaySongWhenBluetoothDeviceConnectedKey
import com.maloy.muzza.constants.AutoSkipNextOnErrorKey
import com.maloy.muzza.constants.CrossfadeDurationKey
import com.maloy.muzza.constants.CrossfadeEnabledKey
import com.maloy.muzza.constants.CrossfadeGaplessKey
import com.maloy.muzza.constants.DiscordTokenKey
import com.maloy.muzza.constants.DiscordUseDetailsKey
import com.maloy.muzza.constants.EnableDiscordRPCKey
import com.maloy.muzza.constants.HideExplicitKey
import com.maloy.muzza.constants.KeepAliveKey
import com.maloy.muzza.constants.MediaSessionConstants.CommandToggleLike
import com.maloy.muzza.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.maloy.muzza.constants.MediaSessionConstants.CommandToggleShuffle
import com.maloy.muzza.constants.MediaSessionConstants.CommandToggleStartRadio
import com.maloy.muzza.constants.PauseListenHistoryKey
import com.maloy.muzza.constants.PersistentQueueKey
import com.maloy.muzza.constants.PlayerVolumeKey
import com.maloy.muzza.constants.RepeatModeKey
import com.maloy.muzza.constants.ShowLyricsKey
import com.maloy.muzza.constants.SkipSilenceKey
import com.maloy.muzza.constants.StopMusicOnTaskClearKey
import com.maloy.muzza.constants.StopPlayingSongWhenMinimumVolumeKey
import com.maloy.muzza.constants.StreamSourceAndroidCreatorKey
import com.maloy.muzza.constants.StreamSourceAndroidVRKey
import com.maloy.muzza.constants.StreamSourceIOSKey
import com.maloy.muzza.constants.StreamSourceTVHTML5Key
import com.maloy.muzza.constants.StreamSourceVisionOSKey
import com.maloy.muzza.constants.StreamSourceWebCreatorKey
import com.maloy.muzza.constants.StreamSourceWebRemixKey
import com.maloy.muzza.db.MusicDatabase
import com.maloy.muzza.db.entities.Event
import com.maloy.muzza.db.entities.FormatEntity
import com.maloy.muzza.db.entities.LyricsEntity
import com.maloy.muzza.db.entities.RelatedSongMap
import com.maloy.muzza.di.DownloadCache
import com.maloy.muzza.di.PlayerCache
import com.maloy.muzza.extensions.SilentHandler
import com.maloy.muzza.extensions.collect
import com.maloy.muzza.extensions.collectLatest
import com.maloy.muzza.extensions.currentMetadata
import com.maloy.muzza.extensions.findNextMediaItemById
import com.maloy.muzza.extensions.getShuffleOrderIndices
import com.maloy.muzza.extensions.mediaItems
import com.maloy.muzza.extensions.metadata
import com.maloy.muzza.extensions.setOffloadEnabled
import com.maloy.muzza.extensions.toMediaItem
import com.maloy.muzza.listentogether.ListenTogetherManager
import com.maloy.muzza.lyrics.LyricsHelper
import com.maloy.muzza.models.MediaMetadata
import com.maloy.muzza.models.PersistQueue
import com.maloy.muzza.models.toMediaMetadata
import com.maloy.muzza.playback.data.AudioSettings
import com.maloy.muzza.playback.queues.EmptyQueue
import com.maloy.muzza.playback.queues.ListQueue
import com.maloy.muzza.playback.queues.Queue
import com.maloy.muzza.playback.queues.YouTubeQueue
import com.maloy.muzza.playback.queues.filterExplicit
import com.maloy.muzza.utils.CoilBitmapLoader
import com.maloy.muzza.utils.DiscordRPC
import com.maloy.muzza.utils.YTPlayerUtils
import com.maloy.muzza.utils.dataStore
import com.maloy.muzza.utils.enumPreference
import com.maloy.muzza.utils.get
import com.maloy.muzza.utils.isInternetAvailable
import com.maloy.muzza.utils.reportException
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.collections.buildSet
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds


@Suppress("DEPRECATION")
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AndroidEntryPoint
class MusicService : MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    @Inject
    lateinit var listenTogetherManager: ListenTogetherManager

    private lateinit var context: Context

    private var scope = CoroutineScope(Dispatchers.Main) + Job()
    private val binder = MusicBinder()

    private lateinit var connectivityManager: ConnectivityManager

    private val audioQuality by enumPreference(this, AudioQualityKey, AudioQuality.AUTO)

    private var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null

    // Shuffle order restored from disk, consumed once by onShuffleModeEnabledChanged so that
    // restoring a shuffled queue does not regenerate a fresh random order.
    private var pendingShuffleOrder: IntArray? = null

    private var consecutivePlaybackErr = 0

    // Media ids already self-healed after a cache position-out-of-range error, so a persistently
    // failing item can't loop (clear cached resource -> retry -> fail -> clear ...).
    private val cacheRecoveredMediaIds = mutableSetOf<String>()

    val currentMediaMetadata = MutableStateFlow<MediaMetadata?>(null)
    private val currentSong = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.song(mediaMetadata?.id)
    }.stateIn(scope, SharingStarted.Lazily, null)
    private val currentFormat = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.format(mediaMetadata?.id)
    }

    // The audio session the enhancer is currently bound to. A LoudnessEnhancer is permanently
    // attached to one AudioTrack session, so it must be released and rebuilt whenever media3
    // hands us a new session id - see initializeLoudnessEnhancer().
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessEnhancerSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var isNormalizationEnabled = false

    /**
     * Offload-safe path for audio normalisation. Shared by both players, since each is built with
     * [createRenderersFactory] and the gain is a property of the track, not of a player instance.
     */
    private val audioGainProcessor = LoudnessGainProcessor()

    /**
     * Whether audio offload is currently requested for [player].
     *
     * media3 1.7.1 exposes no getter for this (no `Player.isOffloadEnabled`, and
     * `TrackSelectionParameters` does not surface the audio offload preferences), so the service
     * mirrors its own preference here. This must stay in sync with every `setOffloadEnabled` call.
     *
     * It gates all `AudioEffect` use: an offloaded `AudioTrack` is rendered on a different thread
     * than the effect chain, and audioflinger refuses to attach one — logging "effect Loudness
     * Enhancer does not support offload flags" and then migrating the effect chain, which pauses,
     * flushes and recreates the output stream (measured 444ms of silence). See [offload].
     */
    private var isOffloadActive = false

    /**
     * Whether audio effects may be attached to the current output.
     *
     * False while offload is requested: audioflinger cannot host an `AudioEffect` on an offloaded
     * track, and merely *trying* costs a stream teardown. With no effect to host there is also no
     * session to advertise, so the control-session broadcasts are skipped as well — they force the
     * same effect-chain migration even without offload.
     */
    private val canUseAudioEffects: Boolean
        get() = !isOffloadActive

    /**
     * Applies an offload setting to [target] and, when [target] is the active player, records it in
     * [isOffloadActive] so the audio-effect gating stays in step with the real player state.
     */
    private fun applyOffload(enabled: Boolean, target: ExoPlayer) {
        target.setOffloadEnabled(enabled)
        if (target === player) {
            isOffloadActive = enabled
        }
    }

    val playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))

    // Mirrored out of DataStore once instead of read at each use site. Both are consulted from
    // the two playback callbacks that run on latency-sensitive threads — checkAndTrackSong on
    // Dispatchers.Main every 2s, onPlaybackStatsReady on the media3 playback thread — and the
    // dataStore.get() delegate is a blocking runBlocking(Dispatchers.IO) DataStore read.
    private val pauseListenHistory =
        MutableStateFlow(dataStore.get(PauseListenHistoryKey, false))
    private val addPlayedSongsToHistory =
        MutableStateFlow(dataStore.get(AddingPlayedSongsToYTMHistoryKey, true))

    val isMuted = MutableStateFlow(false)

    fun setMuted(muted: Boolean) {
        isMuted.value = muted
        applyEffectiveVolume()
    }

    lateinit var sleepTimer: SleepTimer

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    @Inject
    @DownloadCache
    lateinit var downloadCache: SimpleCache

    lateinit var player: ExoPlayer

    private var secondaryPlayer: ExoPlayer? = null
    private var fadingPlayer: ExoPlayer? = null
    private var isCrossfading = false
    private var crossfadeJob: Job? = null
    private lateinit var mediaSession: MediaLibrarySession

    private var isAudioEffectSessionOpened = false
    // The session the system effects panel was told about. Reading player.audioSessionId at close
    // time could close a different session than the one that was opened, leaving the original
    // session with system effects still attached.
    private var openedAudioEffectSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    private var discordRpc: DiscordRPC? = null

    private var lastPlaybackSpeed = 1.0f
    private var discordUpdateJob: Job? = null


    private var bluetoothReceiver: BroadcastReceiver? = null

    private var wasPlayingBeforeMute = false

    private var volumeReceiver: BroadcastReceiver? = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (dataStore.get(StopPlayingSongWhenMinimumVolumeKey, true)) {
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                if (currentVolume == 0 && player.isPlaying) {
                    wasPlayingBeforeMute = true
                    player.pause()
                } else if (currentVolume > 0 && !player.isPlaying && wasPlayingBeforeMute) {
                    player.play()
                    wasPlayingBeforeMute = false
                }
            }
        }
    }


    private val playerInitialized = MutableStateFlow(false)
    val isPlayerReady: StateFlow<Boolean> = playerInitialized.asStateFlow()

    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var hasAudioFocus = false

    private var crossfadeEnabled = false
    private var crossfadeDuration = 5000f
    private var crossfadeGapless = true
    private var crossfadeTriggerJob: Job? = null

    private var playbackStatsListener: PlaybackStatsListener? = null

    private var bluetoothDisconnectReceiver: BroadcastReceiver? = null

    private var playbackTrackingJob: Job? = null
    private val trackedSongs = mutableSetOf<String>()
    private var crossfadeTrackingJob: Job? = null

    private val secondaryPlayerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            secondaryPlayer?.stop()
            secondaryPlayer?.clearMediaItems()
            secondaryPlayer = null
        }
    }

    private fun registerBluetoothReceiver() {
        bluetoothDisconnectReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        if (::player.isInitialized && player.isPlaying) {
                            player.pause()
                            Timber.d("Bluetooth disconnected, paused playback")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }

        registerReceiver(bluetoothDisconnectReceiver, filter)
    }

    private suspend fun checkAndTrackSong(player: Player, trackedSongs: MutableSet<String>) {
        try {
            val mediaItem = player.currentMediaItem ?: return
            val songId = mediaItem.mediaId
            val currentPosition = player.currentPosition
            val duration = player.duration

            if (duration == C.TIME_UNSET || duration <= 0) return

            val isLocal = withContext(Dispatchers.IO) {
                database.song(songId).firstOrNull()?.song?.isLocal == true
            }

            val progress = (currentPosition.toFloat() / duration) * 100

            if (progress >= 30f && !trackedSongs.contains(songId)) {
                trackedSongs.add(songId)

                if (!pauseListenHistory.value) {
                    withContext(Dispatchers.IO) {
                        database.query {
                            incrementTotalPlayTime(songId, currentPosition)
                            try {
                                insert(
                                    Event(
                                        songId = songId,
                                        timestamp = LocalDateTime.now(),
                                        playTime = currentPosition
                                    )
                                )
                            } catch (_: SQLException) {
                            }
                        }
                    }
                }

                if (addPlayedSongsToHistory.value && !isLocal && songId !in offlinePlaybackIds) {
                    withContext(Dispatchers.IO) {
                        registerYouTubeHistory(songId)
                    }
                }
            }
        } catch (_: Exception) {

        }
    }

    private fun startPlaybackTracking() {
        playbackTrackingJob?.cancel()
        playbackTrackingJob = scope.launch {
            trackedSongs.clear()
            while (isActive) {
                try {
                    if (::player.isInitialized && player.playbackState == Player.STATE_READY) {
                        checkAndTrackSong(player, trackedSongs)
                    }

                    if (isCrossfading && fadingPlayer != null) {
                        val fading = fadingPlayer!!
                        if (fading.playbackState == Player.STATE_READY) {
                            checkAndTrackSong(fading, trackedSongs)
                        }
                    }
                } catch (_: Exception) {

                }
                delay(2000.milliseconds)
            }
        }
    }

    private val sessionKey
        get() = YouTube.dataSyncId.takeIf { !it.isNullOrBlank() }
            ?: YouTube.visitorData.takeIf { !it.isNullOrBlank() }
            ?: ""

    private fun cacheKey(mediaId: String) = "${sessionKey}:$mediaId"

    private val playbackUrlCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
                return size > 500
            }
        }
    )

    // Written by the stream-URL prewarm/resolve on Dispatchers.IO and read by the data-source
    // resolver on media3's loader thread, hence ConcurrentHashMap rather than HashMap.
    private val resolvedStreamUrls = ConcurrentHashMap<String, Pair<String, Long>>()

    // Keyed by media id, invalidated per song on media item transition.
    private val playbackSources = ConcurrentHashMap<String, PlaybackSource>()

    /**
     * Media ids whose audio came from local bytes (the download cache) rather than a resolved
     * stream URL. Such a song has no videostats tracking URL, so registering it with YouTube
     * history would mean fetching a player response purely to discard the result — see
     * [registerYouTubeHistory]. Written from the resolver on the loader thread.
     */
    private val offlinePlaybackIds = ConcurrentHashMap.newKeySet<String>()

    // Per-song bookkeeping for recoverSong: which ids already have one in flight, and which are
    // known to carry no related endpoint. Both are read/written from Dispatchers.IO.
    private val recoveringSongIds = ConcurrentHashMap.newKeySet<String>()
    private val noRelatedSongsIds = ConcurrentHashMap.newKeySet<String>()

    // Single-flight guard for stream URL resolution, so the resolver and the prewarm cannot both
    // fire a /player request for the same song. Keyed by media id; entries are dropped once done
    // (see forgetResolveRequest) so an expired URL can be resolved again.
    private val resolveRequests = ConcurrentHashMap<String, CompletableFuture<String>>()

    private val resolveExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "StreamUrlResolve").apply { isDaemon = true }
    }

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    private val _playerFlow = MutableStateFlow<ExoPlayer?>(null)
    val playerFlow = _playerFlow.asStateFlow()

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (!player.isPlaying) {
                        scope.launch(Dispatchers.IO) {
                            discordRpc?.closeRPC()
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (player.isPlaying) {
                        scope.launch {
                            currentSong.value?.let { song ->
                                discordRpc?.updateSong(song, player.currentPosition, player.playbackParameters.speed, dataStore.get(DiscordUseDetailsKey, false))
                            }
                        }
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate() {
        super.onCreate()
        registerReceiver(volumeReceiver, IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
        })
        playerVolume
            .debounce(100.milliseconds)
            .collectLatest(scope) { volume ->
                when {
                    volume == 0f && player.isPlaying && dataStore.get(
                        StopPlayingSongWhenMinimumVolumeKey,
                        true
                    ) -> {
                        wasPlayingBeforeMute = true
                        player.pause()
                    }

                    volume > 0f && !player.isPlaying && wasPlayingBeforeMute && dataStore.get(
                        StopPlayingSongWhenMinimumVolumeKey,
                        true
                    ) -> {
                        player.play()
                        wasPlayingBeforeMute = false
                    }
                }
            }
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player
            )
                .apply {
                    setSmallIcon(R.drawable.small_icon)
                }
        )
        if (dataStore.get(KeepAliveKey, false)) {
            try {
                startService(Intent(this, KeepAlive::class.java))
            } catch (e: Exception) {
                reportException(e)
            }
        } else {
            try {
                stopService(Intent(this, KeepAlive::class.java))
            } catch (e: Exception) {
                reportException(e)
            }
        }
        player = createExoPlayer()
        player.addListener(this@MusicService)
        sleepTimer = SleepTimer(scope, player)
        player.addListener(sleepTimer)
        playbackStatsListener = PlaybackStatsListener(false, this@MusicService)
        player.addAnalyticsListener(playbackStatsListener!!)
        mediaLibrarySessionCallback.apply {
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
        }
        mediaSession = MediaLibrarySession.Builder(this, player, mediaLibrarySessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setBitmapLoader(CoilBitmapLoader(this, scope))
            .build()
        player.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)
        // The player and its media session are fully wired at this point. Signal readiness so
        // consumers waiting on isPlayerReady (PlayerConnection attachment, persistent-queue
        // restore) stop suspending forever.
        playerInitialized.value = true

        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!

        playerVolume.debounce(1000.milliseconds).collect(scope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(1000.milliseconds).collect(scope) {
            updateNotification()
        }

        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged(),
        ) { mediaMetadata, showLyrics ->
            mediaMetadata to showLyrics
        }.collectLatest(scope) { (mediaMetadata, showLyrics) ->
            if (showLyrics && mediaMetadata != null && database
                    .lyrics(mediaMetadata.id)
                    .first() == null
            ) {
                val lyricsWithProvider = lyricsHelper.getLyrics(mediaMetadata)
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = lyricsWithProvider.lyrics,
                            provider = lyricsWithProvider.provider,
                        ),
                    )
                }
            }
        }

        dataStore.data
            .map { it[SkipSilenceKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                player.skipSilenceEnabled = it
            }

        combine(
            playerVolume,
            isMuted,
            dataStore.data
                .map { it[AudioNormalizationKey] ?: true }
                .distinctUntilChanged(),
            currentFormat,
        ) { volume, muted, normalizeAudio, format ->
            AudioSettings(volume, muted, normalizeAudio, format)
        }.collectLatest(scope) { settings ->
            player.volume = if (settings.muted) 0f else settings.volume
            applyNormalizationGain(
                normalizeAudio = settings.normalizeAudio,
                loudnessDb = settings.format?.loudnessDb,
            )
        }

        combine(
            dataStore.data.map { it[AudioOffload] ?: false },
            dataStore.data.map { it[CrossfadeEnabledKey] ?: false }
        ) { offloadPref, crossfadeEnabled ->
            if (crossfadeEnabled) false else offloadPref
        }.distinctUntilChanged()
            .collectLatest(scope) { useOffload ->
                applyOffload(useOffload, player)
                secondaryPlayer?.setOffloadEnabled(useOffload)
                // Offload was just switched on or off, so whether an effect can be attached at all
                // just changed; re-evaluate rather than waiting for the next audio-session change.
                initializeLoudnessEnhancer()
                applyAudioNormalizationSettings()
            }

        dataStore.data
            .map { it[DiscordTokenKey] to (it[EnableDiscordRPCKey] ?: true) }
            .debounce(300.milliseconds)
            .distinctUntilChanged()
            .collect(scope) { (key, enabled) ->
                if (discordRpc?.isRpcRunning() == true) {
                    discordRpc?.closeRPC()
                }
                discordRpc = null
                if (key != null && enabled) {
                    discordRpc = DiscordRPC(this, key)
                    if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                        val mediaId = player.currentMetadata?.id
                        if (mediaId != null) {
                            database.song(mediaId).first()?.let { song ->
                                discordRpc?.updateSong(song, player.currentPosition, player.playbackParameters.speed, dataStore.get(DiscordUseDetailsKey, false))
                            }
                        }
                    }
                }
            }

        dataStore.data
            .map { prefs ->
                (prefs[PauseListenHistoryKey] ?: false) to
                    (prefs[AddingPlayedSongsToYTMHistoryKey] ?: true)
            }
            .distinctUntilChanged()
            .collect(scope) { (pauseHistory, addToHistory) ->
                pauseListenHistory.value = pauseHistory
                addPlayedSongsToHistory.value = addToHistory
            }

        dataStore.data
            .map { prefs ->
                Triple(
                    prefs[CrossfadeEnabledKey] ?: false,
                    prefs[CrossfadeDurationKey] ?: 5,
                    prefs[CrossfadeGaplessKey] ?: true
                )
            }
            .distinctUntilChanged()
            .collect(scope) { (enabled, duration, gapless) ->
                crossfadeEnabled = enabled
                crossfadeDuration = duration.toFloat() * 1000f
                crossfadeGapless = gapless
            }

        dataStore.data
            .map { it[DiscordUseDetailsKey] ?: false }
            .debounce(1000.milliseconds)
            .distinctUntilChanged()
            .collect(scope) { useDetails ->
                if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                    currentSong.value?.let { song ->
                        discordUpdateJob?.cancel()
                        discordUpdateJob = scope.launch {
                            delay(1000.milliseconds)
                            discordRpc?.updateSong(
                                song,
                                player.currentPosition,
                                player.playbackParameters.speed,
                                useDetails
                            )
                        }
                    }
                }
            }

        if (dataStore.get(PersistentQueueKey, true)) {
            runCatching {
                filesDir.resolve(PERSISTENT_QUEUE_FILE).inputStream().use { fis ->
                    ObjectInputStream(fis).use { oos ->
                        oos.readObject() as PersistQueue
                    }
                }
            }.onSuccess { queue ->
                scope.launch {
                    playerInitialized.first { it }
                    if (isActive) {
                        val hideExplicit = dataStore.get(HideExplicitKey, false)
                        // Keep the persisted order/position consistent with any explicit tracks
                        // that get filtered out on restore.
                        val keptIndices = queue.items.indices.filter {
                            !hideExplicit || !queue.items[it].explicit
                        }
                        val remap = keptIndices.withIndex()
                            .associate { (newIndex, oldIndex) -> oldIndex to newIndex }
                        val restoredIndex =
                            queue.mediaItemIndex.coerceIn(0, queue.items.lastIndex.coerceAtLeast(0))
                        val newIndex = remap[restoredIndex]
                            ?: keptIndices.count { it < restoredIndex }
                        val newShuffleOrder = queue.shuffleOrder.mapNotNull { remap[it] }
                        playQueue(
                            queue = ListQueue(
                                title = queue.title,
                                items = keptIndices.map { queue.items[it].toMediaItem() },
                                startIndex = newIndex,
                                position = queue.position
                            ),
                            playWhenReady = false,
                            shuffleModeEnabled = queue.shuffleModeEnabled &&
                                newShuffleOrder.size == keptIndices.size,
                            shuffleOrder = newShuffleOrder,
                        )
                    }
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(30.seconds)
                if (dataStore.get(PersistentQueueKey, true)) {
                    saveQueueToDisk()
                }
            }
            if (discordRpc != null && player.isPlaying) {
                currentSong.value?.let { song ->
                    discordRpc?.updateSong(
                        song,
                        player.currentPosition,
                        player.playbackParameters.speed,
                        dataStore.get(DiscordUseDetailsKey, false)
                    )
                }
            }
        }
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (dataStore.get(AutoPlaySongWhenBluetoothDeviceConnectedKey, true)) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            if (player.playbackState == Player.STATE_READY
                                && !player.isPlaying
                                && dataStore.get(PersistentQueueKey, true)
                            ) {
                                scope.launch {
                                    delay(1000.milliseconds)
                                    player.play()
                                }
                            }
                        }
                    }
                }
            }
        }.apply {
            registerReceiver(this, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            })
        }
        initializeLoudnessEnhancer()

        val screenStateFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, screenStateFilter)
        if (!ensureStartedAsForegroundOrStop()) {
            return
        }

        registerBluetoothReceiver()

        startPlaybackTracking()

        if (!ensureStartedAsForegroundOrStop()) {
            return
        }

        scope.launch {
            dataStore.data
                .map { prefs ->
                    buildSet {
                        if (prefs[StreamSourceWebRemixKey] == false) add("WEB_REMIX")
                        if (prefs[StreamSourceTVHTML5Key] == false) add("TVHTML5")
                        if (prefs[StreamSourceAndroidVRKey] == false) add("ANDROID_VR")
                        if (prefs[StreamSourceIOSKey] != true) add("IOS")
                        if (prefs[StreamSourceVisionOSKey] == false) add("VISIONOS")
                        if (prefs[StreamSourceWebCreatorKey] == false) add("WEB_CREATOR")
                        if (prefs[StreamSourceAndroidCreatorKey] != true) add("ANDROID_CREATOR")
                    }
                }
                .distinctUntilChanged()
                .collect { YTPlayerUtils.disabledStreamClients = it }
        }
    }

    private fun createExoPlayer(): ExoPlayer {
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(createDataSourceFactory()))
            .setRenderersFactory(createRenderersFactory())
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .setDeviceVolumeControlEnabled(true)
            .build()

        player.apply {
            runBlocking {
                val offload = dataStore.get(AudioOffload, false)
                val crossfade = dataStore.get(CrossfadeEnabledKey, false)
                // This is the main player being constructed, so record the initial value here
                // rather than through applyOffload, which only tracks the already-installed player.
                val useOffload = if (crossfade) false else offload
                setOffloadEnabled(useOffload)
                isOffloadActive = useOffload
                skipSilenceEnabled = dataStore.get(SkipSilenceKey, false)
            }
        }
        _playerFlow.value = player
        return player
    }


    /**
     * Rebuilds [loudnessEnhancer] so it is bound to the player's *current* audio session.
     *
     * A LoudnessEnhancer is permanently attached to the AudioTrack session it was created with, and
     * media3 releases and recreates the AudioTrack on every flush (see DefaultAudioSink.flush()),
     * so every repeat and every seek yields a new session id and an EVENT_AUDIO_SESSION_ID. Creating
     * the effect only once left it bound to a destroyed session, and the failure path dropped the
     * reference without releasing it, so a stale enabled effect survived while a new one was built.
     * The result was normalization being applied through the wrong (or several) effect, which
     * compounded across loops on repeat-one and was only cleared by a skip/seek.
     *
     * Session 0 (media3's AUDIO_SESSION_ID_UNSET) is skipped deliberately: no AudioTrack is bound
     * yet, and session 0 is the global output mix, so an effect attached there would affect every
     * app on the device.
     */
    private fun initializeLoudnessEnhancer() {
        if (!canUseAudioEffects) {
            // Offload leaves the AudioTrack on another thread where audioflinger cannot host an
            // effect. Attaching one anyway does not just fail to apply the gain — it makes
            // audioflinger migrate the effect chain and recreate the output stream, which is an
            // audible dropout. Release rather than disable: a disabled effect is still attached.
            releaseLoudnessEnhancer()
            return
        }

        val sessionId = if (::player.isInitialized) {
            player.audioSessionId
        } else {
            C.AUDIO_SESSION_ID_UNSET
        }

        if (sessionId == C.AUDIO_SESSION_ID_UNSET) {
            releaseLoudnessEnhancer()
            return
        }

        if (loudnessEnhancer != null && loudnessEnhancerSessionId == sessionId) {
            // Already bound to the session that is actually playing.
            return
        }

        releaseLoudnessEnhancer()
        try {
            loudnessEnhancer = LoudnessEnhancer(sessionId)
            loudnessEnhancerSessionId = sessionId
            // A freshly created AudioEffect starts out enabled. Keep it inert until
            // applyNormalizationGain() has a gain for this session, so an effect is never left
            // running blind on a session we have not computed anything for yet.
            loudnessEnhancer?.enabled = false
        } catch (_: Exception) {
            releaseLoudnessEnhancer()
        }
    }

    /**
     * Disables and releases the enhancer, if any. Always releases before dropping the reference so
     * a disabled/stale effect can never stay attached to a recycled audio session.
     */
    private fun releaseLoudnessEnhancer() {
        runCatching { loudnessEnhancer?.enabled = false }
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        loudnessEnhancerSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun updateNotification() {
        mediaSession.setCustomLayout(
            listOf(
                CommandButton.Builder()
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            }
                        )
                    )
                    .setIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat
                            REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        }
                    )
                    .setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName(getString(if (currentSong.value?.song?.liked == true) R.string.action_remove_like else R.string.action_like))
                    .setIconResId(if (currentSong.value?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border)
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                if (currentSong.value?.song?.isLocal != true) {
                    CommandButton.Builder()
                        .setDisplayName(getString(R.string.start_radio))
                        .setIconResId(R.drawable.radio)
                        .setSessionCommand(CommandToggleStartRadio)
                        .setEnabled(currentSong.value != null)
                        .build()
                } else {
                    CommandButton.Builder()
                        .setDisplayName(getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                        .setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                        .setSessionCommand(CommandToggleShuffle)
                        .build()
                }
            ) as MutableList<CommandButton>
        )
    }

    private suspend fun recoverSong(
        mediaId: String,
        playbackData: YTPlayerUtils.PlaybackData? = null
    ) {
        // The resolver calls this on every fast-path resolution and can re-enter it many times
        // while one song loads, with no per-mediaId bookkeeping of its own. Without this guard a
        // single song fires a duplicate YouTube.next()/YouTube.related() pair per invocation.
        if (!recoveringSongIds.add(mediaId)) return
        try {
            val song = database.song(mediaId).first()
            val mediaMetadata = withContext(Dispatchers.Main) {
                player.findNextMediaItemById(mediaId)?.metadata
            } ?: return
            // Unlike the YouTube history registration, this fallback genuinely needs the network: there is
            // no local source for a duration that neither the DB row nor the media item carries.
            // It fires only when both are -1, and playbackData (streaming path) covers the rest.
            val duration = song?.song?.duration?.takeIf { it != -1 }
                ?: mediaMetadata.duration.takeIf { it != -1 }
                ?: (playbackData?.videoDetails ?: YTPlayerUtils.playerResponseForMetadata(mediaId)
                    .getOrNull()?.videoDetails)?.lengthSeconds?.toInt()
                ?: -1
            database.query {
                if (song == null) insert(mediaMetadata.copy(duration = duration))
                else if (song.song.duration == -1) update(song.song.copy(duration = duration))
            }
            if (mediaId in noRelatedSongsIds) return
            if (!database.hasRelatedSongs(mediaId)) {
                // Only a definitive "this song has no related endpoint" is worth remembering. A
                // failed request must not be blacklisted, or one transient network error would cost
                // this song its related songs for the rest of the session.
                val nextResult = YouTube.next(WatchEndpoint(videoId = mediaId))
                val relatedEndpoint = nextResult.getOrNull()?.relatedEndpoint
                if (relatedEndpoint == null) {
                    if (nextResult.isSuccess) noRelatedSongsIds.add(mediaId)
                    return
                }
                val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
                database.query {
                    relatedPage.songs
                        .map(SongItem::toMediaMetadata)
                        .onEach(::insert)
                        .map {
                            RelatedSongMap(
                                songId = mediaId,
                                relatedSongId = it.id
                            )
                        }
                        .forEach(::insert)
                }
            }
        } finally {
            recoveringSongIds.remove(mediaId)
        }
    }

    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
        shuffleModeEnabled: Boolean = false,
        shuffleOrder: List<Int>? = null,
    ) {
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main) + Job()
        }
        currentQueue = queue
        queueTitle = null
        pendingShuffleOrder = shuffleOrder?.toIntArray()?.takeIf { shuffleModeEnabled && it.isNotEmpty() }
        player.shuffleModeEnabled = false
        if (queue.preloadItem != null) {
            player.setMediaItem(queue.preloadItem!!.toMediaItem())
            player.prepare()
            player.playWhenReady = playWhenReady
        }

        scope.launch(SilentHandler) {
            val initialStatus = withContext(Dispatchers.IO) {
                queue.getInitialStatus().filterExplicit(dataStore.get(HideExplicitKey, false))
            }
            if (queue.preloadItem != null && player.playbackState == STATE_IDLE) return@launch
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            if (initialStatus.items.isEmpty()) return@launch
            if (queue.preloadItem != null) {
                player.addMediaItems(
                    0,
                    initialStatus.items.subList(0, initialStatus.mediaItemIndex)
                )
                player.addMediaItems(
                    initialStatus.items.subList(
                        initialStatus.mediaItemIndex + 1,
                        initialStatus.items.size
                    )
                )
            } else {
                player.setMediaItems(
                    initialStatus.items,
                    if (initialStatus.mediaItemIndex > 0) initialStatus.mediaItemIndex else 0,
                    initialStatus.position
                )
                player.prepare()
                if (pendingShuffleOrder != null) {
                    player.shuffleModeEnabled = true
                }
                player.playWhenReady = playWhenReady
            }
        }
    }

    private fun clearQueueState() {
        currentQueue = EmptyQueue
        queueTitle = null
        player.shuffleModeEnabled = false
    }

    fun stopAndClearQueue() {
        player.stop()
        player.clearMediaItems()
        clearQueueState()
        if (dataStore.get(PersistentQueueKey, true)) {
            runCatching { filesDir.resolve(PERSISTENT_QUEUE_FILE).delete() }
        }
    }

    fun startRadioSeamlessly() {
        val currentMediaMetadata = player.currentMetadata ?: return
        if (player.currentMediaItemIndex > 0) player.removeMediaItems(
            0,
            player.currentMediaItemIndex
        )
        if (player.currentMediaItemIndex < player.mediaItemCount - 1) player.removeMediaItems(
            player.currentMediaItemIndex + 1,
            player.mediaItemCount
        )
        scope.launch(SilentHandler) {
            val radioQueue =
                YouTubeQueue(
                    title = currentMediaMetadata.title,
                    endpoint = WatchEndpoint(videoId = currentMediaMetadata.id),
                    context = context
                )
            val initialStatus = radioQueue.getInitialStatus()
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            player.addMediaItems(initialStatus.items.drop(1))
            currentQueue = radioQueue
        }
    }

    fun playNext(items: List<MediaItem>) {
        player.addMediaItems(
            if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1,
            items
        )
        player.prepare()
    }

    fun addToQueue(items: List<MediaItem>) {
        player.addMediaItems(items)
        player.prepare()
    }

    fun toggleLibrary() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
        database.query {
            currentSong.value?.let {
                val song = it.song.toggleLike()
                update(song)
            }
        }
    }

    fun toggleStartRadio() {
        startRadioSeamlessly()
    }

    private fun openAudioEffectSession() {
        // Nothing to advertise under offload: no effect is attached, so there is no control session
        // for the UI, and sending this still forces an effect-chain migration and a stream flush.
        if (!canUseAudioEffects) {
            closeAudioEffectSession()
            return
        }
        if (isAudioEffectSessionOpened) return
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            isAudioEffectSessionOpened = true
            openedAudioEffectSessionId = sessionId
            if (isNormalizationEnabled) {
                loudnessEnhancer?.enabled = true
            }

            sendBroadcast(
                Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                },
            )
        } catch (_: Exception) {
            isAudioEffectSessionOpened = false
            openedAudioEffectSessionId = C.AUDIO_SESSION_ID_UNSET
        }
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        loudnessEnhancer?.enabled = false
        // Close the session that was actually opened, not whatever the player reports now.
        val sessionId = openedAudioEffectSessionId
        openedAudioEffectSessionId = C.AUDIO_SESSION_ID_UNSET
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            },
        )
    }

    private fun applyAudioNormalizationSettings() {
        scope.launch {
            val normalizeAudio = dataStore.data.first()[AudioNormalizationKey] ?: true
            val format = currentFormat.first()
            applyNormalizationGain(normalizeAudio, format?.loudnessDb)
        }
    }

    /**
     * Applies the per-track loudness gain, by whichever mechanism the current audio path supports.
     *
     * Under offload this goes through [audioGainProcessor], an `AudioProcessor`, because
     * audioflinger cannot host an `AudioEffect` on an offloaded track and rejects the attempt by
     * recreating the output stream. Everywhere else it uses [LoudnessEnhancer], which the platform
     * can apply without the stream churn - but only while the enhancer is still bound to the
     * session that is actually playing.
     */
    private fun applyNormalizationGain(normalizeAudio: Boolean, loudnessDb: Double?) {
        isNormalizationEnabled = normalizeAudio
        val gain = if (normalizeAudio && loudnessDb != null) {
            (-loudnessDb * 100).toInt().coerceIn(MIN_GAIN_MB, MAX_GAIN_MB)
        } else {
            null
        }

        if (!canUseAudioEffects) {
            releaseLoudnessEnhancer()
            audioGainProcessor.targetGainDb = gain?.toFloat() ?: 0f
            return
        }

        audioGainProcessor.targetGainDb = 0f
        val enhancer = loudnessEnhancer ?: return
        if (!::player.isInitialized || loudnessEnhancerSessionId != player.audioSessionId) {
            // The effect belongs to a session that has already been recycled; the next
            // EVENT_AUDIO_SESSION_ID will rebuild it.
            return
        }

        try {
            if (gain != null) {
                enhancer.setTargetGain(gain)
                enhancer.enabled = true
            } else {
                enhancer.enabled = false
            }
        } catch (_: Exception) {
            // The session went away underneath us: drop the effect rather than retrying against a
            // dead session, so the next one is built cleanly.
            releaseLoudnessEnhancer()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)

        if (!isCrossfading) {
            trackedSongs.clear()
            // Allow a fresh cache self-heal attempt for the newly selected track.
            cacheRecoveredMediaIds.clear()
        }

        // Re-read this song's DB facts and start resolving its stream URL ahead of the loader
        // thread, so neither blocks playback on the way in.
        mediaItem?.mediaId?.let { mediaId ->
            // Facts are re-read once per song, but the caches keyed by media id are long-lived,
            // so drop this song's stale entries here. Otherwise a song cached offline earlier in
            // the session would still be treated as offline after its download was removed, and
            // would never be registered in YouTube history again.
            offlinePlaybackIds.remove(mediaId)
            playbackSources.remove(mediaId)
            prewarmStreamUrl(mediaId)
        }

        if (consecutivePlaybackErr > 0) {
            consecutivePlaybackErr--
        }

        // Media3 keeps the player in STATE_IDLE after a playback error, and seeking to another item
        // while idle does not clear that error. If the item changed because the user navigated,
        // prepare() clears the error and starts the newly selected item.
        if (player.playbackState == STATE_IDLE && player.playerError != null) {
            player.prepare()
            player.playWhenReady = true
        }

        if (player.isPlaying && reason == MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            player.prepare()
            player.play()
        }
        lastPlaybackSpeed = -1.0f
        discordUpdateJob?.cancel()
        if (dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            currentQueue.hasNextPage() && player.currentMediaItemIndex < player.mediaItemCount - 1
        ) {
            scope.launch(SilentHandler) {
                val mediaItems =
                    currentQueue.nextPage().filterExplicit(dataStore.get(HideExplicitKey, false))
                if (player.playbackState != STATE_IDLE) {
                    player.addMediaItems(mediaItems.drop(1))
                }
            }
        }
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        // Intentionally does not tear down the queue on STATE_IDLE: media3 reports STATE_IDLE for
        // playback errors too, and clearing the queue/shuffle there would destroy the user's
        // custom order and position. Teardown happens explicitly in stopAndClearQueue().
        scheduleCrossfade()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED
            )
        ) {
            scheduleCrossfade()
            // Open on genuine playback only. Treating BUFFERING as "playing" meant every rebuffer
            // on a lossy connection could pair with a close and re-open of the effect session, and
            // each of those broadcasts makes audioflinger migrate the effect chain and flush the
            // output stream — an audible dropout. STATE_READY plus isPlaying only holds across a
            // genuine play/stop, which is the one transition the session actually tracks.
            val isEffectSessionRelevant =
                player.playbackState == Player.STATE_READY && player.isPlaying
            if (isEffectSessionRelevant) {
                openAudioEffectSession()
            } else {
                closeAudioEffectSession()
            }
        }
        if (events.contains(Player.EVENT_AUDIO_SESSION_ID)) {
            // A new audio session invalidates any effect bound to the previous one.
            initializeLoudnessEnhancer()
            applyAudioNormalizationSettings()

            // The session the system effects panel was told about is now stale; re-open for the new
            // one so effects follow the AudioTrack instead of being stranded on a recycled id.
            // Skipped when the opened session is already the current one, which happens whenever
            // EVENT_PLAYBACK_STATE_CHANGED shares this batch and opened it a moment ago.
            if (isAudioEffectSessionOpened &&
                openedAudioEffectSessionId != this@MusicService.player.audioSessionId
            ) {
                closeAudioEffectSession()
                if (player.playbackState == Player.STATE_BUFFERING ||
                    player.playbackState == Player.STATE_READY
                ) {
                    openAudioEffectSession()
                }
            }
        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
        }
        if (!player.isPlaying && !events.containsAny(EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                scope.launch {
                    discordRpc?.close()
                }
        }
        if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying) {
            val mediaId = player.currentMetadata?.id
            if (mediaId != null) {
                scope.launch {
                    database.song(mediaId).first()?.let { song ->
                        discordRpc?.updateSong(song, player.currentPosition, player.playbackParameters.speed, dataStore.get(DiscordUseDetailsKey, false))
                    }
                }
            }
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        super.onPlaybackParametersChanged(playbackParameters)
        if (playbackParameters.speed != lastPlaybackSpeed) {
            lastPlaybackSpeed = playbackParameters.speed
            discordUpdateJob?.cancel()
            discordUpdateJob = scope.launch {
                delay(1000.milliseconds)
                if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
                    currentSong.value?.let { song ->
                        discordRpc?.updateSong(song, player.currentPosition, playbackParameters.speed, dataStore.get(DiscordUseDetailsKey, false))
                    }
                }
            }
        }
    }



    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateNotification()
        if (shuffleModeEnabled) {
            val restoredOrder = pendingShuffleOrder
            pendingShuffleOrder = null
            if (restoredOrder != null &&
                restoredOrder.isNotEmpty() &&
                restoredOrder.size == player.mediaItemCount
            ) {
                player.setShuffleOrder(DefaultShuffleOrder(restoredOrder, System.currentTimeMillis()))
                return
            }
            val shuffledIndices = IntArray(player.mediaItemCount) { it }
            shuffledIndices.shuffle()
            shuffledIndices[shuffledIndices.indexOf(player.currentMediaItemIndex)] =
                shuffledIndices[0]
            shuffledIndices[0] = player.currentMediaItemIndex
            player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        scope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE &&
            recoverFromStaleCache(error)
        ) {
            return
        }
        val mediaId = player.currentMediaItem?.mediaId
        if (mediaId != null && error.hasHttpStatus(403)) {
            Timber.tag(TAG).i("CDN 403 for $mediaId — marking WEB_REMIX failed to force fallback clients")
            YTPlayerUtils.markWebRemixFailed(mediaId)
        }
        if (dataStore.get(AutoSkipNextOnErrorKey, false) &&
            isInternetAvailable(this) &&
            player.hasNextMediaItem()
        ) {
            player.seekToNext()
            player.prepare()
            player.playWhenReady = true
            discordUpdateJob?.cancel()
        }
    }

    private fun PlaybackException.hasHttpStatus(status: Int): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == status) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    /**
     * A media cache can record a content length for a song that is shorter than the song itself
     * (e.g. after a truncated write), after which media3's CacheDataSource.open() throws
     * ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE for every request/seek past that point — instantly
     * and without touching the network. The bad length is persisted in the SimpleCache database
     * under filesDir, so neither an app restart nor Android's "clear cache" removes it.
     *
     * The only fix is to drop the cached resource for the affected song so the entry (and its wrong
     * content length) is rebuilt from scratch. Returns true when a retry was scheduled.
     */
    private fun recoverFromStaleCache(error: PlaybackException): Boolean {
        val mediaItem = player.currentMediaItem ?: return false
        val mediaId = mediaItem.mediaId
        if (!cacheRecoveredMediaIds.add(mediaId)) {
            Timber.tag(TAG).w(
                "Stale cache (${error.errorCodeName}) recurred for $mediaId after recovery; not retrying again"
            )
            return false
        }
        val index = player.currentMediaItemIndex
        val positionMs = player.currentPosition
        val playWhenReady = player.playWhenReady
        Timber.tag(TAG).w(
            "Stale cache content length for $mediaId at ${positionMs}ms — " +
                "clearing cached resource and retrying"
        )
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { playerCache.removeResource(mediaId) }
                    .onFailure { Timber.tag(TAG).w(it, "playerCache.removeResource($mediaId) failed") }
                runCatching { downloadCache.removeResource(mediaId) }
                    .onFailure { Timber.tag(TAG).w(it, "downloadCache.removeResource($mediaId) failed") }
            }
            if (isActive) {
                player.seekTo(index, positionMs)
                player.prepare()
                player.playWhenReady = playWhenReady
            }
        }
        return true
    }

    private fun createCacheDataSource(): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    // Streamed audio is written to the song cache here and served from it on
                    // later plays; the LRU evictor keeps it within the configured size. A
                    // truncated write can still make media3 record a short content length, so
                    // the resolver drops such entries before they can break playback (see
                    // createDataSourceFactory).
                    .setUpstreamDataSourceFactory(
                        OkHttpDataSource.Factory(
                            OkHttpClient.Builder()
                                .apply {
                                    // Only install the authenticator when credentials exist: OkHttp
                                    // invokes it on any 407, and force-unwrapping a null
                                    // proxyAuth() crashed the process.
                                    YouTube.proxyAuth?.let { auth ->
                                        proxyAuthenticator { _, response ->
                                            response.request.newBuilder()
                                                .header("Proxy-Authorization", auth)
                                                .build()
                                        }
                                    }
                                }
                                .build()
                        )
                    )
            )
            // Never write downloads from the playback pipeline; explicit downloads go through
            // DownloadUtil into the download cache.
            .setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * DB-derived facts that decide where a song's audio comes from: local file/content URI, or the
     * expected byte length of a cached copy.
     */
    private data class PlaybackSource(
        val isLocal: Boolean,
        val localPath: String?,
        val contentUri: String?,
        val expectedLength: Long?,
    )

    private fun createDataSourceFactory(): DataSource.Factory {
        // DefaultDataSource wraps the caches rather than sitting inside them: it dispatches
        // file/content URIs to its own data sources and only forwards everything else (http(s),
        // and the cache-hit marker below) to the cache chain. Local media therefore bypasses the
        // caches entirely instead of being copied into the song cache.
        return ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, createCacheDataSource())
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")

            // Blocking by necessity (Resolver is synchronous), but a memoised lookup rather than a
            // query: the lambda below is re-entered for every load task and every seek.
            val source = playbackSource(mediaId)

            if (source.isLocal) {
                return@Factory localSourceUri(dataSpec, source)
            }

            val expectedLength = source.expectedLength

            // A stream truncated mid-write (e.g. YouTube throttling) makes media3 record a
            // content length equal to the truncation point. That short length then permanently
            // fails every later open()/seek past it with POSITION_OUT_OF_RANGE. Drop such an
            // entry so it is re-fetched instead of erroring.
            if (expectedLength != null) {
                val cachedLength = ContentMetadata.getContentLength(
                    playerCache.getContentMetadata(mediaId)
                )
                if (cachedLength != C.LENGTH_UNSET.toLong() && cachedLength < expectedLength) {
                    runCatching { playerCache.removeResource(mediaId) }
                }
            }

            // Serve straight from the cache only when the whole resource is present. A partial entry
            // must resolve a stream URL; otherwise, when filling its holes, the cache would fall back
            // to the bare media-id URI and fail to open it.
            if (isFullyCached(mediaId, expectedLength)) {
                offlinePlaybackIds.add(mediaId)
                scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                // Media items use a scheme-less URI (just the media id), which DefaultDataSource
                // would treat as a local file. Give it a scheme it forwards to the cache chain,
                // which resolves the entry by its cache key and never reaches the network.
                return@Factory dataSpec.withUri("$CACHE_URI_SCHEME://$mediaId".toUri())
            }

            // Reuse the already-resolved URL for this song until it expires. Without this, every
            // load task re-runs the whole player resolution (WEB_REMIX + PoToken), which
            // multiplies YouTube requests and triggers its "not a bot" rate limiting on long queues.
            validStreamUrl(mediaId)?.let { cached ->
                return@Factory dataSpec.withUri(cached.toUri())
            }

            return@Factory dataSpec.withUri(resolveStreamUrl(mediaId).toUri())
        }
    }

    private fun localSourceUri(dataSpec: DataSpec, source: PlaybackSource): DataSpec {
        if (!source.contentUri.isNullOrEmpty()) {
            return dataSpec.withUri(source.contentUri.toUri())
        }
        val songPath = source.localPath ?: return dataSpec
        return try {
            val authority = "${packageName}.fileprovider"
            val fileUri = FileProvider.getUriForFile(
                this,
                authority,
                File(songPath)
            )
            dataSpec.withUri(fileUri)
        } catch (_: Exception) {
            dataSpec.withUri(Uri.fromFile(File(songPath)))
        }
    }

    private fun isFullyCached(mediaId: String, expectedLength: Long?): Boolean =
        expectedLength != null &&
            (downloadCache.isCached(mediaId, 0, expectedLength) ||
                playerCache.isCached(mediaId, 0, expectedLength))

    private fun validStreamUrl(mediaId: String): String? =
        resolvedStreamUrls[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.first

    /**
     * Resolves a playable stream URL and memoises it until YouTube expires it. The caller's
     * resolution normally reaches [resolvedStreamUrls] via [prewarmStreamUrl], off the loader
     * thread; this blocking fallback only runs when the prewarm has not landed yet.
     *
     * Note there is deliberately no HEAD/validate round trip here: the URL comes from a
     * successful `/player` response, which [YTPlayerUtils.playerResponseForPlayback] has already
     * validated internally, and re-probing it blocked buffer refill for up to its 5s+5s timeouts.
     */
    private fun resolveStreamUrl(mediaId: String): String {
        validStreamUrl(mediaId)?.let { return it }

        // Single-flight per media id. Two callers can arrive here concurrently: the resolver on
        // the loader thread, and the prewarm kicked off from onMediaItemTransition. Without this
        // both can observe a null cache and fire the same /player request twice, which is exactly
        // what YouTube rate-limits. computeIfAbsent gives us one winner; the loser waits on the
        // same future rather than starting its own request.
        return try {
            resolveRequests.computeIfAbsent(mediaId) {
                CompletableFuture.supplyAsync({
                    runBlocking(Dispatchers.IO) {
                        fetchAndCacheStreamUrl(mediaId)
                    }
                }, resolveExecutor)
            }.join().also { forgetResolveRequest(mediaId) }
        } catch (e: CompletionException) {
            // join() wraps whatever the resolution threw. Unwrap so callers still see the
            // PlaybackException that fetchAndCacheStreamUrl mapped to a user-facing message.
            forgetResolveRequest(mediaId)
            throw (e.cause ?: e)
        }
    }

    /**
     * Performs the actual player resolution and memoises the result. Runs on [resolveExecutor]
     * inside [resolveStreamUrl]'s single-flight, or directly from the prewarm.
     */
    private fun fetchAndCacheStreamUrl(mediaId: String): String {
        val playbackData = runBlocking(Dispatchers.IO) {
            YTPlayerUtils.playerResponseForPlayback(
                mediaId,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
            )
        }.getOrElse { throwable ->
            when (throwable) {
                is PlaybackException -> throw throwable
                is ConnectException, is UnknownHostException -> {
                    throw PlaybackException(
                        getString(R.string.error_no_internet),
                        throwable,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                    )
                }

                is SocketTimeoutException -> {
                    throw PlaybackException(
                        getString(R.string.error_timeout),
                        throwable,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                    )
                }

                else -> throw PlaybackException(
                    getString(R.string.error_unknown),
                    throwable,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR
                )
            }
        }
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
                    perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                    playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                )
            )
        }
        scope.launch(Dispatchers.IO) { recoverSong(mediaId, playbackData) }

        val streamUrl = playbackData.streamUrl
        resolvedStreamUrls[mediaId] =
            streamUrl to System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L)
        playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl?.let {
            playbackUrlCache[cacheKey(mediaId)] = it
        }
        return streamUrl
    }

    /**
     * Drops a finished single-flight entry so the next resolution for this id can run again — the
     * memoised URL has a finite lifetime, and without this the completed future would be returned
     * forever once the URL expired.
     */
    private fun forgetResolveRequest(mediaId: String) {
        val future = resolveRequests[mediaId] ?: return
        if (future.isDone) resolveRequests.remove(mediaId, future)
    }

    /**
     * DB facts for [mediaId], read once and reused.
     *
     * The resolver runs on media3's loader thread — one single-thread executor per media period —
     * and re-enters it for every load task and every seek. Reading them inline there stalled
     * buffer refill on a busy database, so they are memoised instead and refreshed on every media
     * item transition (see [onMediaItemTransition]) to keep a song that was downloaded or
     * resolved while it sat in the queue from being pinned to a stale answer.
     */
    private fun playbackSource(mediaId: String): PlaybackSource =
        playbackSources[mediaId] ?: loadPlaybackSource(mediaId).also { playbackSources[mediaId] = it }

    private fun loadPlaybackSource(mediaId: String): PlaybackSource {
        val song = runBlocking(Dispatchers.IO) { database.song(mediaId).firstOrNull()?.song }
        return PlaybackSource(
            isLocal = song?.isLocal == true,
            localPath = song?.localPath,
            contentUri = song?.contentUri,
            expectedLength = runBlocking(Dispatchers.IO) {
                database.format(mediaId).firstOrNull()?.contentLength
            },
        )
    }

    /**
     * Resolves a stream URL for [mediaId] ahead of the player asking for one, so that the loader
     * thread finds [resolvedStreamUrls] populated and never has to block on the `/player` request.
     * No-op for local songs and for songs that are already fully cached, which need no URL at all.
     */
    private fun prewarmStreamUrl(mediaId: String) {
        if (resolvedStreamUrls.containsKey(mediaId)) return
        scope.launch(Dispatchers.IO) {
            forgetResolveRequest(mediaId)
            if (resolvedStreamUrls.containsKey(mediaId)) return@launch
            val source = playbackSource(mediaId)
            if (source.isLocal || isFullyCached(mediaId, source.expectedLength)) return@launch
            // Goes through the same single-flight as the resolver, so a concurrent request from the
            // loader thread joins this one instead of duplicating it.
            runCatching { resolveStreamUrl(mediaId) }
        }
    }

    private fun createRenderersFactory() =
        object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = DefaultAudioSink.Builder(this@MusicService)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        // Gain first, while the stream is still the decoder's native 16-bit PCM;
                        // SonicAudioProcessor converts to float further down.
                        audioGainProcessor,
                        SilenceSkippingAudioProcessor(2_000_000, 0.01f, 2_000_000, 0, 256),
                        SonicAudioProcessor()
                    )
                )
                .build()
        }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats
    ) {
        val mediaItem =
            eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem

        if (isCrossfading) {
            if (playbackStats.totalPlayTimeMs >= 30000 && !pauseListenHistory.value) {
                if (!trackedSongs.contains(mediaItem.mediaId)) {
                    database.query {
                        incrementTotalPlayTime(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
                        try {
                            insert(
                                Event(
                                    songId = mediaItem.mediaId,
                                    timestamp = LocalDateTime.now(),
                                    playTime = playbackStats.totalPlayTimeMs
                                )
                            )
                        } catch (_: SQLException) {
                        }
                    }
                }
            }
            return
        }

        if (playbackStats.totalPlayTimeMs >= 30000 && !pauseListenHistory.value) {
            database.query {
                incrementTotalPlayTime(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
                try {
                    insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = LocalDateTime.now(),
                            playTime = playbackStats.totalPlayTimeMs
                        )
                    )
                } catch (_: SQLException) {
                }
            }
        }
        if (playbackStats.totalPlayTimeMs >= 30000 && addPlayedSongsToHistory.value) {
            scope.launch(Dispatchers.IO) {
                registerYouTubeHistory(mediaItem.mediaId)
            }
        }
    }

    /**
     * Adds [mediaId] to the YouTube Music play history, if a videostats tracking URL for it is
     * already known.
     *
     * Deliberately does not resolve a player response to obtain a missing URL. [playbackUrlCache]
     * is only populated when a stream URL was resolved, so for a song served from the download
     * cache there is no tracking URL to find — and asking YouTube for one costs a full /player
     * request plus a PoToken WebView round trip, mid-song, purely to throw the result away. That
     * measured 8.6s on a metered connection and is unbounded on a lossy one, where requests hang
     * rather than failing. Songs that actually streamed always have the URL and are unaffected.
     */
    private suspend fun registerYouTubeHistory(mediaId: String) {
        val playbackUrl = playbackUrlCache[cacheKey(mediaId)]
        if (playbackUrl == null) {
            Timber.tag(TAG).d("No playback tracking URL for $mediaId, skipping YouTube history registration")
            return
        }
        YouTube.registerPlayback(null, playbackUrl).onFailure { reportException(it) }
    }

    private fun saveQueueToDisk() {
        if (player.playbackState == STATE_IDLE) {
            // media3 reports STATE_IDLE for playback errors as well; only delete the persisted
            // queue when playback was actually stopped, so an error can't wipe it.
            if (player.playerError == null) {
                filesDir.resolve(PERSISTENT_QUEUE_FILE).delete()
            }
            return
        }
        val persistQueue = PersistQueue(
            title = queueTitle,
            items = player.mediaItems.mapNotNull { it.metadata },
            mediaItemIndex = player.currentMediaItemIndex,
            position = player.currentPosition,
            shuffleModeEnabled = player.shuffleModeEnabled,
            shuffleOrder = player.getShuffleOrderIndices(),
        )
        runCatching {
            filesDir.resolve(PERSISTENT_QUEUE_FILE).outputStream().use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(persistQueue)
                }
            }
        }.onFailure {
            reportException(it)
        }
    }

    override fun onDestroy() {
        if (!::player.isInitialized) {
            try {
                scope.cancel()
            } catch (_: Exception) {
            }
            super.onDestroy()
            return
        }
        unregisterReceiver(screenStateReceiver)
        volumeReceiver?.let { unregisterReceiver(it) }
        volumeReceiver = null
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDisk()
        }
        if (discordRpc?.isRpcRunning() == true) {
            discordRpc?.closeRPC()
        }
        discordRpc = null
        releaseLoudnessEnhancer()
        mediaSession.release()
        player.removeListener(this)
        player.removeListener(sleepTimer)
        player.release()
        bluetoothReceiver?.let {
            unregisterReceiver(it)
        }
        bluetoothDisconnectReceiver = null
        abandonAudioFocus()
        super.onDestroy()
        playbackTrackingJob?.cancel()
        playbackTrackingJob = null
        crossfadeTrackingJob?.cancel()
        crossfadeTrackingJob = null
        trackedSongs.clear()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (dataStore.get(StopMusicOnTaskClearKey, false)) {
            player.stop()
            clearQueueState()
            stopSelf()
            return
        }
        if (!player.isPlaying) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            scheduleCrossfade()
        }
    }

    private fun scheduleCrossfade() {
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        if (!crossfadeEnabled || player.duration == C.TIME_UNSET || player.duration <= crossfadeDuration) return
        if (crossfadeGapless && isNextItemGapless()) return
        if (!player.hasNextMediaItem()) return

        val triggerTime = player.duration - crossfadeDuration.toLong()
        val delayMs = triggerTime - player.currentPosition
        if (delayMs <= 0) return

        val targetMediaId = player.currentMediaItem?.mediaId

        crossfadeTriggerJob = scope.launch {
            delay(delayMs.milliseconds)
            if (isActive && player.isPlaying && player.currentMediaItem?.mediaId == targetMediaId) {
                startCrossfade()
            }
        }
    }

    private fun startCrossfade() {
        if (isCrossfading) return

        val savedRepeatMode = player.repeatMode
        val targetIndex = if (savedRepeatMode == REPEAT_MODE_ONE) {
            player.currentMediaItemIndex
        } else {
            player.nextMediaItemIndex
        }
        if (targetIndex == C.INDEX_UNSET) return

        secondaryPlayer = createExoPlayerWithoutAudioFocus()
        val secPlayer = secondaryPlayer!!
        secPlayer.addListener(secondaryPlayerListener)

        val itemCount = player.mediaItemCount
        val items = mutableListOf<MediaItem>()
        for (i in 0 until itemCount) {
            items.add(player.getMediaItemAt(i))
        }

        secPlayer.setMediaItems(items)
        secPlayer.seekTo(targetIndex, 0)
        secPlayer.volume = 0f
        secPlayer.repeatMode = savedRepeatMode
        secPlayer.shuffleModeEnabled = player.shuffleModeEnabled
        secPlayer.prepare()
        secPlayer.playWhenReady = true

        performCrossfadeSwap()
    }

    private fun performCrossfadeSwap() {
        isCrossfading = true
        val nextPlayer = secondaryPlayer ?: return
        val currentPlayer = player

        fadingPlayer = currentPlayer
        player = nextPlayer
        _playerFlow.value = player
        secondaryPlayer = null

        // Re-derive from the player that is now primary. It was built with the crossfade rule
        // (offload forced off), so this normally clears the flag; deriving it rather than assuming
        // keeps the audio-effect gating correct for the new primary either way.
        isOffloadActive = dataStore.get(AudioOffload, false) &&
            !dataStore.get(CrossfadeEnabledKey, false)

        try {
            currentPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to release audio focus from old player")
        }

        fadingPlayer?.removeListener(this)
        fadingPlayer?.removeListener(sleepTimer)

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )

        val syncListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isCrossfading && fadingPlayer != null) {
                    if (isPlaying) {
                        fadingPlayer?.play()
                    } else {
                        fadingPlayer?.pause()
                    }
                } else {
                    player.removeListener(this)
                }
            }
        }
        player.addListener(syncListener)

        nextPlayer.removeListener(secondaryPlayerListener)

        val oldPlaybackStatsListener = playbackStatsListener
        if (oldPlaybackStatsListener != null) {
            currentPlayer.removeAnalyticsListener(oldPlaybackStatsListener)
            val newPlaybackStatsListener = PlaybackStatsListener(false, this@MusicService)
            playbackStatsListener = newPlaybackStatsListener
            nextPlayer.addAnalyticsListener(newPlaybackStatsListener)
        }

        nextPlayer.addListener(this)
        nextPlayer.addListener(sleepTimer)

        // The incoming player owns its own AudioTrack and therefore its own audio session. media3
        // does not replay EVENT_AUDIO_SESSION_ID to listeners attached after the fact, so without
        // this the enhancer stays bound to the outgoing player's session - which cleanupCrossfade()
        // is about to release - and applyNormalizationGain() then no-ops until the next flush or seek.
        initializeLoudnessEnhancer()
        applyAudioNormalizationSettings()

        sleepTimer.player = player

        try {
            (mediaSession as MediaSession).player = player
        } catch (e: Exception) {
            Timber.e(e, "Failed to swap player in MediaSession")
        }

        trackedSongs.clear()

        val currentMediaItem = player.currentMediaItem
        if (currentMediaItem != null) {
            trackedSongs.add(currentMediaItem.mediaId)
        }

        crossfadeJob = scope.launch {
            val duration = crossfadeDuration.toLong()
            val steps = 40
            val stepTime = duration / steps

            val currentVolume = fadingPlayer?.volume ?: 1f
            player.volume = 0f
            for (i in 0..steps) {
                if (!isActive) break

                while (!player.isPlaying && isActive) {
                    delay(50.milliseconds)
                }

                val progress = i / steps.toFloat()

                val fadeOut = (1.0f - progress) * (1.0f - progress)
                val fadeIn = progress * progress

                try {
                    fadingPlayer?.volume = (currentVolume * fadeOut).coerceIn(0f, 1f)
                    player.volume = (currentVolume * fadeIn).coerceIn(0f, 1f)
                } catch (_: Exception) {
                    break
                }

                delay(stepTime.milliseconds)
            }

            try {
                fadingPlayer?.volume = 0f
                player.volume = currentVolume
                cleanupCrossfade()
            } catch (_: Exception) {
            }
        }
    }

    fun createExoPlayerWithoutAudioFocus(): ExoPlayer {
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(createDataSourceFactory()))
            .setRenderersFactory(createRenderersFactory())
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false,
            )
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .setDeviceVolumeControlEnabled(true)
            .build()

        player.apply {
            runBlocking {
                val offload = dataStore.get(AudioOffload, false)
                val crossfade = dataStore.get(CrossfadeEnabledKey, false)
                // Crossfade implies offload must be off, same as for the primary player. Applying
                // the raw preference here would leave this player offloaded even though it exists
                // to crossfade, and it becomes the primary in performCrossfadeSwap().
                applyOffload(if (crossfade) false else offload, player)
                skipSilenceEnabled = dataStore.get(SkipSilenceKey, false)
            }
        }
        return player
    }

    private fun abandonAudioFocus() {
        if (audioFocusListener != null) {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.abandonAudioFocus(audioFocusListener!!)
            hasAudioFocus = false
        }
    }

    private fun applyEffectiveVolume() {
        if (!::player.isInitialized || isCrossfading) return
        player.volume = if (isMuted.value) 0f else playerVolume.value
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun ensureStartedAsForegroundOrStop(): Boolean =
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.music_player),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            val pending =
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val notification: Notification =
                NotificationCompat
                    .Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.music_player))
                    .setContentText("")
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentIntent(pending)
                    .setOngoing(true)
                    .build()
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Timber.tag(TAG).w(e, "Foreground service start not allowed; stopping service to avoid ANR")
            stopSelf()
            false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to enter foreground; stopping service to avoid ANR")
            reportException(e)
            stopSelf()
            false
        }

    private fun cleanupCrossfade() {
        fadingPlayer?.stop()
        fadingPlayer?.clearMediaItems()
        fadingPlayer?.release()
        fadingPlayer = null
        isCrossfading = false
        applyEffectiveVolume()
        if (!player.isPlaying) {
            trackedSongs.clear()
        }
    }

    private fun isNextItemGapless(): Boolean {
        val current = player.currentMediaItem?.mediaMetadata ?: return false
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        val next = player.getMediaItemAt(nextIndex).mediaMetadata
        return current.albumTitle != null && current.albumTitle == next.albumTitle
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    companion object {
        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"

        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
        private const val CACHE_URI_SCHEME = "muzza-cache"
        private const val MAX_GAIN_MB = 800
        private const val MIN_GAIN_MB = -800
    }
}
