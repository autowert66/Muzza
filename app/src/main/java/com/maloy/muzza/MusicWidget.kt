package com.maloy.muzza

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.imageLoader
import coil.request.ImageRequest
import com.google.common.util.concurrent.ListenableFuture
import com.maloy.muzza.constants.MediaSessionConstants
import com.maloy.muzza.playback.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MusicWidget : AppWidgetProvider() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runnable: Runnable

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        ensureController(context)
        appWidgetIds.forEach { appWidgetId ->
            updateWidget(context, appWidgetManager, appWidgetId)
        }
        startProgressUpdater(context)
    }

    override fun onEnabled(context: Context) {
        ensureController(context)
        startProgressUpdater(context)
    }

    override fun onDisabled(context: Context) {
        stopProgressUpdater()
        releaseController()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (val action = intent.action) {
            ACTION_PLAY_PAUSE,
            ACTION_PREV,
            ACTION_NEXT,
            ACTION_SHUFFLE,
            ACTION_LIKE,
            ACTION_REPLAY -> {
                val pendingResult = goAsync()
                performAction(context, action) {
                    updateAllWidgets(context)
                    pendingResult.finish()
                }
            }
        }
    }

    private fun startProgressUpdater(context: Context) {
        runnable = Runnable {
            updateAllWidgets(context)
            handler.postDelayed(runnable, 1000)
        }
        handler.post(runnable)
    }

    private fun stopProgressUpdater() {
        handler.removeCallbacks(runnable)
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.maloy.muzza.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "com.maloy.muzza.ACTION_PREV"
        const val ACTION_NEXT = "com.maloy.muzza.ACTION_NEXT"
        const val ACTION_SHUFFLE = "com.maloy.muzza.ACTION_SHUFFLE"
        const val ACTION_LIKE = "com.maloy.muzza.ACTION_LIKE"
        const val ACTION_REPLAY = "com.maloy.muzza.ACTION_REPLAY"

        @Volatile
        private var appContext: Context? = null
        private var controllerFuture: ListenableFuture<MediaController>? = null
        private var controller: MediaController? = null

        private val controllerListener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                appContext?.let { updateAllWidgets(it) }
            }
        }

        private fun ensureController(context: Context, onReady: (MediaController?) -> Unit = {}) {
            val app = context.applicationContext
            appContext = app

            controller?.let {
                onReady(it)
                return
            }

            val pending = controllerFuture
            if (pending != null && !pending.isDone) {
                pending.addListener(
                    { onReady(controller) },
                    ContextCompat.getMainExecutor(app)
                )
                return
            }
            if (pending != null) {
                controllerFuture = null
            }

            val future = MediaController.Builder(
                app,
                SessionToken(app, ComponentName(app, MusicService::class.java))
            ).buildAsync()
            controllerFuture = future
            future.addListener(Runnable {
                if (controllerFuture !== future) return@Runnable
                try {
                    val created = future.get()
                    created.addListener(controllerListener)
                    controller = created
                    updateAllWidgets(app)
                    onReady(created)
                } catch (e: Exception) {
                    controllerFuture = null
                    onReady(null)
                }
            }, ContextCompat.getMainExecutor(app))
        }

        private fun releaseController() {
            controller?.removeListener(controllerListener)
            controller = null
            controllerFuture?.let { MediaController.releaseFuture(it) }
            controllerFuture = null
        }

        private fun performAction(
            context: Context,
            action: String,
            onDone: () -> Unit,
        ) {
            ensureController(context) { c ->
                if (c == null) {
                    onDone()
                    return@ensureController
                }
                when (action) {
                    ACTION_PLAY_PAUSE -> if (c.isPlaying) c.pause() else c.play()
                    ACTION_PREV -> if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) {
                        c.seekTo(0)
                    } else {
                        c.seekToPreviousMediaItem()
                    }

                    ACTION_NEXT -> c.seekToNextMediaItem()
                    ACTION_SHUFFLE -> c.sendCustomCommand(MediaSessionConstants.CommandToggleShuffle, Bundle.EMPTY)
                    ACTION_LIKE -> c.sendCustomCommand(MediaSessionConstants.CommandToggleLike, Bundle.EMPTY)
                    ACTION_REPLAY -> c.sendCustomCommand(MediaSessionConstants.CommandToggleRepeatMode, Bundle.EMPTY)
                }
                onDone()
            }
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, MusicWidget::class.java)
            )
            widgetIds.forEach { updateWidget(context, appWidgetManager, it) }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_music)
            val controller = controller
            val metadata = controller?.mediaMetadata

            if (controller != null && controller.currentMediaItem != null && metadata != null) {
                views.setTextViewText(R.id.widget_track_title, metadata.title)
                views.setTextViewText(R.id.widget_artist, metadata.artist ?: metadata.subtitle)
                val playPauseIcon = if (controller.playWhenReady) R.drawable.pause else R.drawable.play
                views.setImageViewResource(R.id.widget_play_pause, playPauseIcon)
                val shuffleIcon = if (controller.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle
                views.setImageViewResource(R.id.widget_shuffle, shuffleIcon)
                val likeIcon = R.drawable.favorite
                views.setImageViewResource(R.id.widget_like, likeIcon)
                if (controller.repeatMode == Player.REPEAT_MODE_ONE) {
                    views.setInt(R.id.widget_play_pause, "setColorFilter", context.getColor(R.color.light_blue_50))
                } else {
                    views.setInt(R.id.widget_play_pause, "setColorFilter", context.getColor(android.R.color.transparent))
                }
                val currentPos = formatTime(controller.currentPosition)
                val duration = formatTime(controller.duration)
                views.setTextViewText(R.id.widget_current_time, currentPos)
                views.setTextViewText(R.id.widget_total_time, duration)
                val progress = if (controller.duration > 0) {
                    (controller.currentPosition * 100 / controller.duration).toInt()
                } else 0
                views.setProgressBar(R.id.widget_progress, 100, progress, false)
                val thumbnailUrl = metadata.artworkUri?.toString()
                if (!thumbnailUrl.isNullOrEmpty()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val request = ImageRequest.Builder(context)
                                .data(thumbnailUrl)
                                .build()
                            val drawable = context.imageLoader.execute(request).drawable
                            drawable?.let {
                                views.setImageViewBitmap(R.id.widget_album_art, it.toBitmap())
                                appWidgetManager.updateAppWidget(appWidgetId, views)
                            }
                        } catch (e: Exception) {
                            views.setImageViewResource(R.id.widget_album_art, R.drawable.music_note)
                            appWidgetManager.updateAppWidget(appWidgetId, views)
                        }
                    }
                } else {
                    views.setImageViewResource(R.id.widget_album_art, R.drawable.music_note)
                }
            }

            views.setOnClickPendingIntent(R.id.widget_play_pause, getBroadcastPendingIntent(context, ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_prev, getBroadcastPendingIntent(context, ACTION_PREV))
            views.setOnClickPendingIntent(R.id.widget_next, getBroadcastPendingIntent(context, ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widget_shuffle, getBroadcastPendingIntent(context, ACTION_SHUFFLE))
            views.setOnClickPendingIntent(R.id.widget_like, getBroadcastPendingIntent(context, ACTION_LIKE))

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun getBroadcastPendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, MusicWidget::class.java).apply {
                this.action = action
                flags = Intent.FLAG_RECEIVER_FOREGROUND
            }
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        @SuppressLint("DefaultLocale")
        private fun formatTime(millis: Long): String {
            return if (millis < 0) "0:00" else String.format(
                "%d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(millis),
                TimeUnit.MILLISECONDS.toSeconds(millis) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
            )
        }
    }
}
