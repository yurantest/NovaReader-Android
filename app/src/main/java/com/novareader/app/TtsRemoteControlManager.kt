package com.novareader.app

import android.app.NotificationManager
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File

class TtsRemoteControlManager(
    context: Context,
    private val onPlayRequested: () -> Unit,
    private val onPauseRequested: () -> Unit,
    private val onStopRequested: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var currentlyPlaying = false
    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile private var ttsStopped = false
    @Volatile private var updatingFromCode = false

    private val handler = Handler(Looper.getMainLooper())

    private var bookTitle: String = ""
    private var bookAuthor: String = ""
    private var coverFile: File? = null

    companion object {
        private const val TAG = "NovaReader.BtRemote"
        private const val CHANNEL_ID = "novareader_tts_playback"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.novareader.app.TTS_PLAY"
        const val ACTION_PAUSE = "com.novareader.app.TTS_PAUSE"
        const val ACTION_STOP = "com.novareader.app.TTS_STOP"
    }

    private val session = MediaSessionCompat(appContext, "NovaReaderTTS").apply {
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                if (updatingFromCode) return
                NovaLog.d(TAG, "onPlay()")
                currentlyPlaying = true
                ttsStopped = false
                updatePlaybackState()
                updateNotification()
                onPlayRequested()
            }
            override fun onPause() {
                if (updatingFromCode) return
                NovaLog.d(TAG, "onPause()")
                currentlyPlaying = false
                updatePlaybackState()
                updateNotification()
                onPauseRequested()
            }
            override fun onStop() {
                if (updatingFromCode) return
                NovaLog.d(TAG, "onStop()")
                currentlyPlaying = false
                ttsStopped = true
                updatePlaybackState()
                cancelNotification()
                onStopRequested()
            }
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (currentlyPlaying) onPause() else onPlay()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> { onPlay(); return true }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> { onPause(); return true }
                        KeyEvent.KEYCODE_MEDIA_STOP -> { onStop(); return true }
                        KeyEvent.KEYCODE_HEADSETHOOK -> {
                            if (currentlyPlaying) onPause() else onPlay()
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
        setMediaButtonReceiver(null)
        isActive = true
        setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
    }

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (updatingFromCode) return
            when (intent?.action) {
                ACTION_PLAY -> {
                    currentlyPlaying = true
                    ttsStopped = false
                    updatePlaybackState()
                    updateNotification()
                    onPlayRequested()
                }
                ACTION_PAUSE -> {
                    currentlyPlaying = false
                    updatePlaybackState()
                    updateNotification()
                    onPauseRequested()
                }
                ACTION_STOP -> {
                    currentlyPlaying = false
                    ttsStopped = true
                    updatePlaybackState()
                    cancelNotification()
                    onStopRequested()
                }
            }
        }
    }
    private var actionReceiverRegistered = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                NovaLog.d(TAG, "Наушники отключены")
                if (updatingFromCode) return
                currentlyPlaying = false
                updatePlaybackState()
                updateNotification()
                onPauseRequested()
            }
        }
    }
    private var noisyReceiverRegistered = false

    init {
        updatePlaybackState()
        requestAudioFocus()

        ContextCompat.registerReceiver(
            appContext, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyReceiverRegistered = true

        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_STOP)
        }
        ContextCompat.registerReceiver(
            appContext, actionReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        actionReceiverRegistered = true
    }

    fun setPlaying(playing: Boolean) {
        if (currentlyPlaying != playing) {
            NovaLog.d(TAG, "setPlaying($playing)")
        }
        if (playing) ttsStopped = false
        currentlyPlaying = playing
        updatePlaybackState()
        updateNotification()
    }

    fun updatePlaybackPosition(positionMs: Long) {
        // в кастомном уведомлении прогресс-бар не нужен, поэтому игнорируем
    }

    fun stopAndHideNotification() {
        ttsStopped = true
        currentlyPlaying = false
        updatePlaybackState()
        cancelNotification()
    }

    fun setBookMetadata(title: String, author: String, cover: File?, totalDurationMs: Long = 0L) {
        bookTitle = title
        bookAuthor = author
        coverFile = cover
        updateNotification()
    }

    private fun updatePlaybackState() {
        updatingFromCode = true
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (currentlyPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f
                ).build()
        )
        handler.post { updatingFromCode = false }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Озвучка NovaReader",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление чтением вслух"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        if (ttsStopped) {
            cancelNotification()
            return
        }
        if (!currentlyPlaying && bookTitle.isBlank()) return
        createNotificationChannel()

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val openAppIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            appContext, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleAction = if (currentlyPlaying) ACTION_PAUSE else ACTION_PLAY
        val toggleIntent = PendingIntent.getBroadcast(appContext, 1,
            Intent(toggleAction).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = PendingIntent.getBroadcast(appContext, 3,
            Intent(ACTION_STOP).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val deleteIntent = PendingIntent.getBroadcast(appContext, 4,
            Intent(ACTION_STOP).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val customView = RemoteViews(appContext.packageName, R.layout.notification_tts)
        customView.setTextViewText(R.id.notifTitle, bookTitle.ifBlank { "NovaReader" })

        coverFile?.let { file ->
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.let { bmp ->
                    customView.setImageViewBitmap(R.id.notifIcon, bmp)
                }
            }
        }

        val toggleIcon = if (currentlyPlaying)
            android.R.drawable.ic_media_pause
        else
            android.R.drawable.ic_media_play
        customView.setImageViewResource(R.id.notifPlayPause, toggleIcon)
        customView.setOnClickPendingIntent(R.id.notifPlayPause, toggleIntent)

        customView.setImageViewResource(R.id.notifStop,
            android.R.drawable.ic_menu_close_clear_cancel)
        customView.setOnClickPendingIntent(R.id.notifStop, stopIntent)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            // Прозрачная иконка — не видна в строке статуса
            .setSmallIcon(R.drawable.ic_notification_transparent)
            .setContentIntent(pendingOpenApp)
            .setOngoing(currentlyPlaying)
            .setDeleteIntent(deleteIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .setContentTitle(bookTitle.ifBlank { "NovaReader" })
            .setContentText(bookAuthor)

        // ВАЖНО: setColorized(true) — рисует цветной фон под кастомным
        // layout. Можно убрать, если не нужен.
        // .setColorized(true)

        nm.notify(NOTIFICATION_ID, builder.build())
    }

    private fun cancelNotification() {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { change ->
                    NovaLog.d(TAG, "onAudioFocusChange: $change")
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            if (currentlyPlaying) {
                                currentlyPlaying = false
                                updatePlaybackState()
                                updateNotification()
                            }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> { }
                    }
                }
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            if (currentlyPlaying) {
                                currentlyPlaying = false
                                updatePlaybackState()
                                updateNotification()
                            }
                        }
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun release() {
        if (noisyReceiverRegistered) {
            try { appContext.unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
            noisyReceiverRegistered = false
        }
        if (actionReceiverRegistered) {
            try { appContext.unregisterReceiver(actionReceiver) } catch (_: Exception) {}
            actionReceiverRegistered = false
        }
        abandonAudioFocus()
        cancelNotification()
        session.isActive = false
        session.release()
    }
}