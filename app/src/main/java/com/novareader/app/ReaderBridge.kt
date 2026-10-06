package com.novareader.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "NovaReader.Bridge"

object ReaderBridgeHolder {
    var webView: WebView? = null
}

class ReaderBridge(private val context: Context) {

    private var webView: WebView? = null

    private val tts = TTSManager(context)
    private val book = BookManager(context)
    private val settings = SettingsManager(context)
    private val library = LibraryManager(context)
    private val fonts = FontManager(context)
    private val corrections = CorrectionsManager(context)

    private var silenceTrack: AudioTrack? = null
    private var silenceThread: Thread? = null
    @Volatile private var silenceRunning = false
    /** true после первого write() в silent track — аудиопуть реально открыт */
    @Volatile private var silenceReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var playbackServiceStarted = false
    @Volatile private var ttsStartGeneration = 0L

    @Volatile private var jsRespondingToServiceCommand = false

    fun libraryManager(): LibraryManager = library
    fun bookManager(): BookManager = book
    fun fontManager(): FontManager = fonts

    var onTtsPlaybackChanged: ((Boolean) -> Unit)? = null
    private var ttsPlaying = false

    private fun setTtsPlaying(playing: Boolean) {
        if (ttsPlaying == playing) return
        ttsPlaying = playing
        webView?.post { onTtsPlaybackChanged?.invoke(playing) }
    }

    fun attachWebView(wv: WebView) {
        webView = wv
        tts.init()
        // Сервис + уведомление плеера поднимаем ТОЛЬКО при Play (onTTSText/resumeTTS),
        // а не при открытии книги — иначе в шторке сразу висит плеер без озвучки.
        if (TtsPlaybackService.instance != null) {
            playbackServiceStarted = true
            installServiceCallbacks()
        } else {
            playbackServiceStarted = false
        }

        tts.onWordRange = { start, end ->
            runJs("window.onTTSWordRange && window.onTTSWordRange($start,$end);")
        }
        tts.onDone = {
            TtsPlaybackService.instance?.setPlaying(true)
            runJs("window.ttsNext && window.ttsNext();")
        }
        tts.onError = { msg ->
            setTtsPlaying(false)
            TtsPlaybackService.instance?.setPlaying(false)
            val escaped = msg.replace("'", "\\'")
            runJs("window.onTTSError && window.onTTSError('$escaped')")
        }
        tts.onPlaybackInterrupted = { setTtsPlaying(false) }
        tts.onPlaybackResumed    = { setTtsPlaying(true)  }
        tts.onPlaybackStopped    = { setTtsPlaying(false) }
    }

    /**
     * Всегда переустанавливает колбэки сервиса, даже если он уже запущен.
     * Это критично после разблокировки экрана: Activity пересоздаётся,
     * WebView — новый, а колбэки сервиса указывали на старый (уничтоженный)
     * WebView, из-за чего runJs() уходил в никуда, и TTS зависал.
     */
    private fun startPlaybackService() {
        val intent = Intent(context, TtsPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        playbackServiceStarted = true
        installServiceCallbacks()
    }

    /** Гарантирует foreground-сервис только когда пользователь реально слушает TTS. */
    private fun ensurePlaybackService() {
        if (TtsPlaybackService.instance != null) {
            playbackServiceStarted = true
            installServiceCallbacks()
            return
        }
        startPlaybackService()
    }

    private fun installServiceCallbacks() {
        webView?.postDelayed({
            TtsPlaybackService.instance?.let { service ->
                service.onPlayRequested = {
                    jsRespondingToServiceCommand = true
                    runJs("window.onTtsRemotePlay && window.onTtsRemotePlay();")
                    webView?.postDelayed({ jsRespondingToServiceCommand = false }, 500)
                }
                service.onPauseRequested = {
                    jsRespondingToServiceCommand = true
                    runJs("window.onTtsRemotePause && window.onTtsRemotePause();")
                    webView?.postDelayed({ jsRespondingToServiceCommand = false }, 500)
                }
                service.onStopRequested = {
                    jsRespondingToServiceCommand = true
                    runJs("window.onTtsRemoteStop && window.onTtsRemoteStop();")
                    webView?.postDelayed({ jsRespondingToServiceCommand = false }, 500)
                }
                updateNotificationMetadata()
                NovaLog.d(TAG, "TtsPlaybackService колбэки (пере)установлены")
            }
        }, 300)
    }

    fun updateNotificationMetadata() {
        val service = TtsPlaybackService.instance ?: return

        val bookPrefs = context.getSharedPreferences("novareader_book", Context.MODE_PRIVATE)
        val currentPath = bookPrefs.getString("current_book_path", "") ?: ""
        val currentName = bookPrefs.getString("current_book_name", "") ?: ""

        if (currentPath.isBlank() || currentName.isBlank()) return

        var coverFile: File? = null
        try {
            val booksArr = JSONArray(library.getBooks())
            for (i in 0 until booksArr.length()) {
                val b = booksArr.getJSONObject(i)
                if (b.optString("relative_path") == currentPath) {
                    val coverPath = b.optString("cover_path")
                    if (coverPath.isNotBlank()) {
                        coverFile = File(NovaStorage.rootDir(context), coverPath)
                    }
                    break
                }
            }
        } catch (_: Exception) {}

        service.setBookMetadata(currentName, "", coverFile)
    }

    private fun runJs(js: String) {
        webView?.post { webView?.evaluateJavascript(js, null) }
    }

    fun setCurrentBook(relativePath: String, displayName: String, type: String) =
        book.setCurrentBook(relativePath, displayName, type)

    // ── TTS ─────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun isTTSReady(): String = tts.isReady.toString()

    @JavascriptInterface
    fun onTTSText(text: String) {
        // Новый запрос озвучки получает поколение. Если старый silent-track
        // callback вернётся позже, он не имеет права запустить старую фразу.
        val generation = ++ttsStartGeneration
        NovaLog.d(TAG, "onTTSText(): generation=$generation, ready=${tts.isReady}, len=${text.length}")
        // Первый Play → поднимаем сервис и уведомление в шторке
        ensurePlaybackService()
        setTtsPlaying(true)
        if (!jsRespondingToServiceCommand) {
            TtsPlaybackService.instance?.setPlaying(true)
        }
        updateNotificationMetadata()
        // Сначала прогреваем аудиопуть (silent AudioTrack), потом speak —
        // иначе на Xiaomi/HyperOS первые слоги / короткие фразы («Глава 1»)
        // уходят в никуда, пока маршрут ещё поднимается.
        startSilentPlayback {
            if (generation != ttsStartGeneration) {
                NovaLog.d(TAG, "onTTSText(): устаревший silent callback generation=$generation пропущен")
                return@startSilentPlayback
            }
            tts.speak(text)
        }
    }

    /** Тексты следующих предложений — для предзагрузки облачным движком (см. TTSManager.setLookahead). */
    @JavascriptInterface
    fun prefetchTTS(textsJson: String) {
        tts.setLookahead(textsJson)
    }

    @JavascriptInterface
    fun setRate(rate: Float) = tts.setRate(rate)

    @JavascriptInterface
    fun pauseTTS() {
        ++ttsStartGeneration
        tts.stop()
        setTtsPlaying(false)
        if (!jsRespondingToServiceCommand) {
            TtsPlaybackService.instance?.setPlaying(false)
        }
    }

    @JavascriptInterface
    fun resumeTTS() {
        ensurePlaybackService()
        setTtsPlaying(true)
        if (!jsRespondingToServiceCommand) {
            TtsPlaybackService.instance?.setPlaying(true)
        }
        startSilentPlayback()
    }

    @JavascriptInterface
    fun stopTTS() {
        ++ttsStartGeneration
        tts.stop()
        setTtsPlaying(false)
        stopSilentPlayback()
        // Полностью гасим сервис и уведомление — плеер в шторке только во время сессии TTS.
        TtsPlaybackService.instance?.stopService()
        playbackServiceStarted = false
    }

    @JavascriptInterface
    fun setPreferredEngine(engineName: String) {
        NovaLog.d(TAG, "setPreferredEngine('$engineName') игнорируется")
    }

    @JavascriptInterface
    fun setVoice(voiceId: String) {
        NovaLog.d(TAG, "setVoice('$voiceId') игнорируется")
    }

    @JavascriptInterface
    fun getAvailableEngines(): String = JSONArray().toString()

    @JavascriptInterface
    fun getSystemEngines(): String = JSONArray().toString()

    @JavascriptInterface
    fun getEngineVoices(engineName: String): String = JSONArray().toString()

    @JavascriptInterface
    fun getPiperVoices(): String = JSONArray().toString()

    @JavascriptInterface
    fun checkVoiceAvailability(voiceId: String): Boolean = false

    @JavascriptInterface
    fun downloadVoice(voiceId: String) { }

    @JavascriptInterface
    fun getVoicesDir(): String = context.filesDir.resolve("piper_voices").absolutePath

    // ── Фиктивное воспроизведение ──────────────────────────────────────

    /**
     * @param onReady вызывается на main-thread, когда аудиопуть уже открыт
     *                (или сразу, если silent track уже крутится).
     */
    private fun startSilentPlayback(onReady: (() -> Unit)? = null) {
        if (silenceRunning && silenceReady) {
            onReady?.invoke()
            return
        }
        if (silenceRunning) {
            // Трек стартует, ждём ready
            if (onReady != null) {
                val start = System.currentTimeMillis()
                fun poll() {
                    if (silenceReady || System.currentTimeMillis() - start > 500) {
                        onReady()
                    } else {
                        mainHandler.postDelayed({ poll() }, 20)
                    }
                }
                poll()
            }
            return
        }

        silenceRunning = true
        silenceReady = false

        silenceThread = Thread {
            var readyPosted = false
            try {
                val sampleRate = 16000
                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(2048)

                val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                // Тот же usage, что у TTS — один маршрут, без переключения
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                        AudioTrack.MODE_STREAM
                    )
                }

                silenceTrack = track
                // Ненулевая минимальная громкость на части прошивок лучше
                // «открывает» DAC; 0f иногда оставляет путь «спящим».
                track.setVolume(0.01f)
                track.play()

                val silence = ShortArray(bufferSize / 2)
                // Первый write — реально открывает выход; после него можно speak
                try {
                    track.write(silence, 0, silence.size)
                } catch (_: Exception) {}

                silenceReady = true
                if (!readyPosted) {
                    readyPosted = true
                    mainHandler.post {
                        onReady?.invoke()
                    }
                }

                while (silenceRunning) {
                    try {
                        track.write(silence, 0, silence.size)
                    } catch (_: Exception) { break }
                }

                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
                silenceTrack = null
            } catch (e: Exception) {
                NovaLog.e(TAG, "Silent playback ошибка: ${e.message}")
                silenceRunning = false
                silenceReady = false
                if (!readyPosted) {
                    readyPosted = true
                    // Даже при ошибке не блокируем озвучку
                    mainHandler.post { onReady?.invoke() }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopSilentPlayback() {
        silenceRunning = false
        silenceReady = false
        silenceThread?.interrupt()
        silenceThread = null
        try { silenceTrack?.stop() } catch (_: Exception) {}
        try { silenceTrack?.release() } catch (_: Exception) {}
        silenceTrack = null
    }

    // ── Коррекции TTS ───────────────────────────────────────────────────

    var onShowTtsCorrection: (() -> Unit)? = null

    @JavascriptInterface
    fun showTTSCorrection() {
        webView?.post { onShowTtsCorrection?.invoke() }
    }

    @JavascriptInterface
    fun getTTSCorrectionsCombined(): String {
        val relPath = try {
            JSONObject(book.getBookData()).optString("path").ifBlank { null }
        } catch (_: Exception) { null }

        val combined = corrections.getCombined(relPath)
        val arr = JSONArray()
        combined.forEach { e ->
            arr.put(JSONObject().apply {
                put("wrong", e.wrong)
                put("correct", e.correct)
                put("case_insensitive", e.caseInsensitive)
            })
        }
        return arr.toString()
    }

    // ── Книга / позиция / закладки / заметки ────────────────────────────

    @JavascriptInterface
    fun getBookData(): String = book.getBookData()

    @JavascriptInterface
    fun getPosition(): String = book.getPosition()

    @JavascriptInterface
    fun savePosition(positionJson: String, progress: Float) = book.savePosition(positionJson, progress)

    @JavascriptInterface
    fun getBookmarks(): String = book.getBookmarks()

    @JavascriptInterface
    fun saveBookmark(label: String, progress: Float, positionJson: String) = book.saveBookmark(label, progress, positionJson)

    @JavascriptInterface
    fun removeBookmark(bookmarkId: String) = book.removeBookmark(bookmarkId)

    @JavascriptInterface
    fun getHighlights(): String = book.getHighlights()

    @JavascriptInterface
    fun saveHighlight(text: String, color: String, style: String, cfi: String) = book.saveHighlight(text, color, style, cfi)

    @JavascriptInterface
    fun removeHighlight(highlightId: String) = book.removeHighlight(highlightId)

    @JavascriptInterface
    fun getNotes(): String = book.getNotes()

    @JavascriptInterface
    fun saveNote(highlightId: String, noteText: String, cfiJson: String) = book.saveNote(highlightId, noteText, cfiJson)

    @JavascriptInterface
    fun removeNote(noteId: String) = book.removeNote(noteId)

    @JavascriptInterface
    fun updateNote(noteId: String, newText: String) = book.updateNote(noteId, newText)

    @JavascriptInterface
    fun saveQuoteImage(base64Data: String) = book.saveQuoteImage(base64Data)

    // ── Настройки / тема / язык ─────────────────────────────────────────

    @JavascriptInterface
    fun getTheme(): String = settings.getTheme()

    @JavascriptInterface
    fun getLanguage(): String = settings.getLanguage()

    @JavascriptInterface
    fun getSettings(): String {
        val obj = JSONObject(settings.getSettings())
        obj.put("font_faces", JSONArray(fonts.getFontFaces()))
        return obj.toString()
    }

    @JavascriptInterface
    fun saveSetting(keyValueJson: String) = settings.saveSetting(keyValueJson)

    @JavascriptInterface
    fun getTTSCorrections(): String = settings.getTTSCorrections()

    @JavascriptInterface
    fun saveTTSCorrections(correctionsJson: String) = settings.saveTTSCorrections(correctionsJson)

    // ── Разное ──────────────────────────────────────────────────────────

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("novareader", text))
    }

    @JavascriptInterface
    fun onMouseMove() { }

    @JavascriptInterface
    fun onSectionChanged(index: Int) = NovaLog.d(TAG, "Секция: $index")

    @JavascriptInterface
    fun onBookReady() {
        NovaLog.d(TAG, "Книга готова")
        updateNotificationMetadata()
    }

    @JavascriptInterface
    fun log(message: String) = NovaLog.d("$TAG.JS", message)

    var onShowLibrary: (() -> Unit)? = null

    @JavascriptInterface
    fun showLibrary() {
        webView?.post { onShowLibrary?.invoke() }
    }

    var onShowSettings: (() -> Unit)? = null

    @JavascriptInterface
    fun showSettings() {
        webView?.post { onShowSettings?.invoke() }
    }

    /**
     * После возврата из системных настроек синтеза речи MainActivity
     * пересоздаёт TextToSpeech (иначе остаётся старый движок до рестарта).
     */
    @Volatile
    private var recreateTtsOnNextResume = false

    fun consumeRecreateTtsRequest(): Boolean {
        val need = recreateTtsOnNextResume
        recreateTtsOnNextResume = false
        return need
    }

    @JavascriptInterface
    fun openSystemTtsSettings() {
        webView?.post {
            try {
                // Пометить: при следующем onResume Activity нужно recreate TTS
                recreateTtsOnNextResume = true
                val intent = Intent("com.android.settings.TTS_SETTINGS")
                // Без NEW_TASK — тогда возврат идёт в нашу Activity и срабатывает onResume
                if (context !is android.app.Activity) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                recreateTtsOnNextResume = false
                NovaLog.e(TAG, "Не удалось открыть настройки TTS: ${e.message}")
                // Fallback: общий экран настроек языка/ввода
                try {
                    recreateTtsOnNextResume = true
                    val fallback = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    if (context !is android.app.Activity) {
                        fallback.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(fallback)
                } catch (e2: Exception) {
                    recreateTtsOnNextResume = false
                    NovaLog.e(TAG, "Fallback настроек тоже не открылся: ${e2.message}")
                }
            }
        }
    }

    @JavascriptInterface
    fun getFontFaces(): String = fonts.getFontFaces()

    var onImportFont: (() -> Unit)? = null

    @JavascriptInterface
    fun importFont() {
        webView?.post { onImportFont?.invoke() }
    }

    @JavascriptInterface
    fun isDevMode(): Boolean = settings.devMode

    @JavascriptInterface
    fun setDevMode(enabled: Boolean) {
        settings.devMode = enabled
    }

    var onShowNativeLogs: (() -> Unit)? = null

    @JavascriptInterface
    fun showNativeLogs() {
        webView?.post { onShowNativeLogs?.invoke() }
    }

    fun recreateTts() {
        NovaLog.d(TAG, "recreateTts() — пересоздаём движок после смены в системе")
        tts.stop()
        stopSilentPlayback()
        tts.recreate()
    }

    fun onDestroy() {
        stopSilentPlayback()
        TtsPlaybackService.instance?.stopService()
        playbackServiceStarted = false
        tts.destroy()
    }
}