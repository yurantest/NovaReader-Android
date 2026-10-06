package com.novareader.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import java.io.File

/**
 * Foreground-сервис TTS.
 *
 * Для шторки — кастомный RemoteViews (notification_tts.xml).
 * Для HyperOS-островка / системного медиа-плеера:
 *   - MediaSession + MediaMetadata (title/artist/cover)
 *   - NotificationCompat.MediaStyle с sessionToken
 *
 * Без MediaStyle + metadata Xiaomi/HyperOS не показывает плеер в островке,
 * даже если MediaSession активен.
 *
 * Аудиофокус здесь НЕ запрашивается (им владеет TTSManager).
 * ACTION_AUDIO_BECOMING_NOISY не ловится (ложные паузы при старте AudioTrack).
 */
class TtsPlaybackService : Service() {

    companion object {
        private const val TAG = "NovaReader.TtsService"
        private const val CHANNEL_ID = "novareader_tts_playback"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.novareader.app.TTS_PLAY"
        const val ACTION_PAUSE = "com.novareader.app.TTS_PAUSE"
        const val ACTION_STOP = "com.novareader.app.TTS_STOP"

        @Volatile
        var instance: TtsPlaybackService? = null
            private set
    }

    var onPlayRequested: (() -> Unit)? = null
    var onPauseRequested: (() -> Unit)? = null
    var onStopRequested: (() -> Unit)? = null

    private lateinit var mediaSession: MediaSessionCompat

    private var currentlyPlaying = false
    @Volatile private var ttsStopped = false
    @Volatile private var updatingFromCode = false
    private val handler = Handler(Looper.getMainLooper())
    private var cpuWakeLock: PowerManager.WakeLock? = null

    private var bookTitle: String = ""
    private var bookAuthor: String = ""
    private var coverFile: File? = null
    private var coverBitmap: Bitmap? = null

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (updatingFromCode) return
            when (intent?.action) {
                ACTION_PLAY -> {
                    currentlyPlaying = true
                    ttsStopped = false
                    setSessionActive(true)
                    updateMediaMetadata()
                    updatePlaybackState()
                    updateNotification()
                    onPlayRequested?.invoke()
                }
                ACTION_PAUSE -> {
                    currentlyPlaying = false
                    updatePlaybackState()
                    updateNotification()
                    onPauseRequested?.invoke()
                }
                ACTION_STOP -> {
                    hideForegroundNotification()
                    onStopRequested?.invoke()
                }
            }
        }
    }
    private var actionReceiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannel()
        initMediaSession()
        registerReceivers()

        NovaLog.d(TAG, "TtsPlaybackService создан (без аудио-фокуса)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundInternal()
        return START_NOT_STICKY
    }

    private fun updateCpuWakeLock(playing: Boolean) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (playing) {
                if (cpuWakeLock == null) {
                    cpuWakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "$packageName:NovaReaderTTS"
                    ).apply { setReferenceCounted(false) }
                }
                if (!cpuWakeLock!!.isHeld) {
                    cpuWakeLock!!.acquire()
                    NovaLog.d(TAG, "PARTIAL_WAKE_LOCK включён для TTS")
                }
            } else {
                cpuWakeLock?.takeIf { it.isHeld }?.release()
                NovaLog.d(TAG, "PARTIAL_WAKE_LOCK выключен")
            }
        } catch (e: Exception) {
            NovaLog.e(TAG, "WakeLock ошибка: ${e.message}")
        }
    }

    fun setPlaying(playing: Boolean) {
        if (currentlyPlaying != playing) {
            NovaLog.d(TAG, "setPlaying($playing)")
        }
        if (playing) {
            ttsStopped = false
            // Поднимаем нашу сессию «наверх» — HyperOS чаще показывает последнюю active
            setSessionActive(true)
            updateMediaMetadata()
        }
        currentlyPlaying = playing
        updateCpuWakeLock(playing)
        updatePlaybackState()
        updateNotification()
    }

    fun stopAndHideNotification() {
        hideForegroundNotification()
        NovaLog.d(TAG, "stopAndHideNotification: foreground removed")
    }

    /**
     * У started foreground-сервиса cancel() недостаточен —
     * нужен stopForeground(REMOVE), иначе уведомление «липнет».
     */
    private fun hideForegroundNotification() {
        ttsStopped = true
        currentlyPlaying = false
        updateCpuWakeLock(false)

        updatingFromCode = true
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(0)
                .setState(
                    PlaybackStateCompat.STATE_STOPPED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    0f
                )
                .build()
        )
        // Очищаем metadata — иначе HyperOS может оставить «призрак» в островке
        mediaSession.setMetadata(null)
        setSessionActive(false)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ updatingFromCode = false }, 500)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        cancelNotification()
    }

    fun setBookMetadata(title: String, author: String, cover: File?) {
        bookTitle = title
        bookAuthor = author
        coverFile = cover
        coverBitmap = null
        cover?.takeIf { it.exists() }?.let { file ->
            try {
                // Ужимаем обложку — MediaSession/островку хватит ~512px
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                var sample = 1
                val maxSide = 512
                while (opts.outWidth / sample > maxSide || opts.outHeight / sample > maxSide) {
                    sample *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                coverBitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            } catch (e: Exception) {
                NovaLog.e(TAG, "cover decode: ${e.message}")
            }
        }
        updateMediaMetadata()
        updateNotification()
    }

    fun stopService() {
        currentlyPlaying = false
        updateCpuWakeLock(false)
        cancelNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun initMediaSession() {
        // Уникальный tag (package + id) — HyperOS/система реже путает сессии
        // разных читалок, если tag/identity отличаются.
        mediaSession = MediaSessionCompat(this, "com.novareader.app.session.tts").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (updatingFromCode) return
                    NovaLog.d(TAG, "onPlay()")
                    currentlyPlaying = true
                    ttsStopped = false
                    setSessionActive(true)
                    updatePlaybackState()
                    updateNotification()
                    onPlayRequested?.invoke()
                }
                override fun onPause() {
                    if (updatingFromCode) return
                    NovaLog.d(TAG, "onPause()")
                    currentlyPlaying = false
                    updatePlaybackState()
                    updateNotification()
                    onPauseRequested?.invoke()
                }
                override fun onStop() {
                    if (updatingFromCode) return
                    NovaLog.d(TAG, "onStop()")
                    hideForegroundNotification()
                    onStopRequested?.invoke()
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

            // Куда открывать приложение по тапу на островок
            val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (launch != null) {
                setSessionActivity(
                    PendingIntent.getActivity(
                        this@TtsPlaybackService, 200, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            // Локальный STREAM_MUSIC — сессия явно «наш» медиа-плеер, не VoIP/assistant
            setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)

            // MediaButtonReceiver из манифеста — корректная маршрутизация кнопок
            val mbr = PendingIntent.getBroadcast(
                this@TtsPlaybackService, 0,
                Intent(Intent.ACTION_MEDIA_BUTTON).setClass(
                    this@TtsPlaybackService,
                    androidx.media.session.MediaButtonReceiver::class.java
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setMediaButtonReceiver(mbr)

            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            // Не активируем сразу — только когда реально играет (см. setSessionActive)
            isActive = false
        }
        updatePlaybackState()
    }

    /**
     * Активная сессия = кандидат в островок HyperOS.
     * Держим active только пока пользователь слушает/на паузе в NovaReader.
     * На stop — гасим, чтобы чужая читалка не «склеивалась» с нашей сессией.
     */
    private fun setSessionActive(active: Boolean) {
        if (!::mediaSession.isInitialized) return
        if (mediaSession.isActive != active) {
            mediaSession.isActive = active
            NovaLog.d(TAG, "MediaSession.isActive=$active")
        }
    }

    /** Title/artist/cover — то, что HyperOS показывает в островке. */
    private fun updateMediaMetadata() {
        if (!::mediaSession.isInitialized) return
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, bookTitle.ifBlank { "NovaReader" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, bookAuthor.ifBlank { " " })
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "NovaReader")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, bookTitle.ifBlank { "NovaReader" })
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, bookAuthor)
        coverBitmap?.let { bmp ->
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bmp)
        }
        mediaSession.setMetadata(builder.build())
    }

    private fun updatePlaybackState() {
        updatingFromCode = true
        mediaSession.setPlaybackState(
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
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    if (currentlyPlaying) 1f else 0f
                )
                .build()
        )
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ updatingFromCode = false }, 500)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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

    private fun startForegroundInternal() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this, 100, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleAction = if (currentlyPlaying) ACTION_PAUSE else ACTION_PLAY
        val toggleIntent = PendingIntent.getBroadcast(
            this, 101,
            Intent(toggleAction).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getBroadcast(
            this, 103,
            Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Кастомный вид — как был (шторка уведомлений)
        val customView = RemoteViews(packageName, R.layout.notification_tts)
        customView.setTextViewText(R.id.notifTitle, bookTitle.ifBlank { "NovaReader" })

        coverBitmap?.let { bmp ->
            customView.setImageViewBitmap(R.id.notifIcon, bmp)
        } ?: coverFile?.let { file ->
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

        customView.setImageViewResource(
            R.id.notifStop,
            android.R.drawable.ic_menu_close_clear_cancel
        )
        customView.setOnClickPendingIntent(R.id.notifStop, stopIntent)

        // Actions для MediaStyle (системный компактный вид / fallback)
        val playPauseAction = NotificationCompat.Action(
            if (currentlyPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play,
            if (currentlyPlaying) "Pause" else "Play",
            toggleIntent
        )
        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            stopIntent
        )

        // MediaStyle + sessionToken — это то, что HyperOS/остров берёт для плеера
        val mediaStyle = MediaNotificationCompat.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_transparent)
            .setContentIntent(pendingOpenApp)
            .setOngoing(currentlyPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setContentTitle(bookTitle.ifBlank { "NovaReader" })
            .setContentText(bookAuthor)
            .setLargeIcon(coverBitmap)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(mediaStyle)
            // Кастомный layout поверх — шторка остаётся вашей
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .build()
    }

    private fun updateNotification() {
        if (ttsStopped) {
            cancelNotification()
            return
        }
        if (!currentlyPlaying && bookTitle.isBlank()) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun cancelNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_STOP)
        }
        ContextCompat.registerReceiver(
            this, actionReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        actionReceiverRegistered = true
    }

    override fun onDestroy() {
        updateCpuWakeLock(false)
        NovaLog.d(TAG, "TtsPlaybackService onDestroy")
        instance = null

        if (actionReceiverRegistered) {
            try { unregisterReceiver(actionReceiver) } catch (_: Exception) {}
            actionReceiverRegistered = false
        }
        mediaSession.setMetadata(null)
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }
}
