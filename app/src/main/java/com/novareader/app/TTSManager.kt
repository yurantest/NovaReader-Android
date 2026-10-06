package com.novareader.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * Единственный режим озвучки — Native Android TextToSpeech.
 *
 * Сознательно НЕ содержит: выбор движка, выбор голоса, Piper, Edge-TTS,
 * хранение engineId/voiceId. Какой движок и голос использовать — решается
 * ПОЛЬЗОВАТЕЛЕМ в системных настройках Android (Настройки → Языки и ввод →
 * Синтез речи), NovaReader об этом ничего не хранит и не решает.
 *
 * Если пользователь поменяет системный движок TTS прямо во время работы
 * приложения — вызывающий код должен позвать recreate(), чтобы получить
 * новый TextToSpeech, привязанный к новому дефолтному движку.
 */
class TTSManager(private val context: Context) {

    companion object {
        private const val TAG = "NovaReader.TTS"
    }

    private var tts: TextToSpeech? = null
    private var pendingText: String? = null
    @Volatile private var currentUtteranceId: String? = null

    /** Тексты следующих предложений (JSON-массив) — уходят движку вместе со speak()
     *  в параметре "nova_prefetch", чтобы облачный движок синтезировал их заранее,
     *  пока играет текущая фраза. Обычные движки лишний параметр игнорируют. */
    @Volatile private var lookaheadJson: String? = null

    fun setLookahead(json: String?) { lookaheadJson = json }

    var isReady = false
        private set

    var onWordRange: ((startMs: Int, endMs: Int) -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Вызывается когда TTS замерла из-за временной потери фокуса (звонок,
     *  будильник и т.п.) — уже не играет, но собирается сама продолжить.
     *  Используется, например, чтобы на время звонка снять "кофеин"
     *  (ScreenWakeManager) — слушать всё равно нечего, а как только
     *  дозвонившийся положит трубку, onPlaybackResumed включит его назад. */
    var onPlaybackInterrupted: (() -> Unit)? = null

    /** Вызывается когда TTS сама, без участия пользователя, продолжила
     *  ту же фразу после возврата аудиофокуса. */
    var onPlaybackResumed: (() -> Unit)? = null

    /** Вызывается при ОКОНЧАТЕЛЬНОЙ остановке (пользователь нажал стоп/
     *  пауза, либо фокус потерян навсегда — AUDIOFOCUS_LOSS, не transient). */
    var onPlaybackStopped: (() -> Unit)? = null

    // ── Аудиофокус: пока озвучка играет, просим систему приглушить/
    //    остановить остальной звук — в т.ч. звуки уведомлений других
    //    приложений (если они, как и большинство, уважают audio focus).
    //    Права на это не нужны — обычный AudioManager-механизм.
    //    AUDIOFOCUS_GAIN_TRANSIENT (без MAY_DUCK) просит именно замолчать,
    //    а не просто убавить громкость — так короткие "дзынь" уведомлений
    //    не проигрываются поверх речи вовсе, а не просто становятся тише.
    //
    //    Звонки эту же логику не используют вообще: телефония живёт на
    //    отдельном, более приоритетном канале (STREAM_RING/STREAM_VOICE_CALL)
    //    и не спрашивает нашего разрешения — вместо этого система сама
    //    отбирает у нас фокус (LOSS_TRANSIENT на время звонка), и по этому
    //    сигналу мы сами замолкаем. Разговор слышен всегда, независимо от
    //    того, что вообще делает наш AudioFocusRequest.
    //
    //    LOSS_TRANSIENT(_CAN_DUCK) — временная потеря (типично звонок):
    //    молчим, ЗАПОМИНАЕМ последнюю непроговорённую фразу и НЕ отдаём
    //    focusRequest — тогда система сама пришлёт AUDIOFOCUS_GAIN, когда
    //    звонок закончится, и мы САМИ, без участия пользователя, повторим
    //    именно ту фразу, на которой прервались (Android TTS не умеет
    //    возобновлять с середины произнесённого, поэтому повторяем фразу
    //    целиком — ближайшее доступное к "заморозке").
    //
    //    LOSS (без _TRANSIENT) — потеря навсегда (например, пользователь
    //    сам запустил музыку/другое приложение с постоянным фокусом):
    //    останавливаемся насовсем и отдаём фокус, как обычный stop().

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var focusRequest: AudioFocusRequest? = null

    /** Текст последней озвученной/озвучиваемой фразы — нужен, чтобы
     *  повторить её после временного прерывания (звонок). */
    private var lastText: String? = null

    /** true, пока мы молчим из-за LOSS_TRANSIENT и ждём AUDIOFOCUS_GAIN,
     *  чтобы САМИ (без вызова из JS) повторить lastText. */
    private var pausedForInterruption = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                NovaLog.d(TAG, "Аудиофокус временно потерян ($change, вероятно звонок) — замираем")
                tts?.stop() // именно stop(), НЕ вызываем публичный stop() — фокус не отдаём
                pausedForInterruption = true
                onPlaybackInterrupted?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                NovaLog.d(TAG, "Аудиофокус потерян насовсем — останавливаем TTS")
                pausedForInterruption = false
                tts?.stop()
                abandonAudioFocus()
                onPlaybackStopped?.invoke()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedForInterruption) {
                    pausedForInterruption = false
                    val text = lastText
                    if (!text.isNullOrEmpty()) {
                        NovaLog.d(TAG, "Аудиофокус вернулся — продолжаем с той же фразы")
                        val utteranceId = UUID.randomUUID().toString()
                        currentUtteranceId = utteranceId
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                        onPlaybackResumed?.invoke()
                    }
                }
            }
            else -> {}
        }
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) {
                // USAGE_MEDIA + GAIN: тот же путь, что и silent AudioTrack.
                // USAGE_ASSISTANT открывал другой маршрут — первые слоги «съедались».
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
            }
            val result = am.requestAudioFocus(focusRequest!!)
            NovaLog.d(TAG, "requestAudioFocus() result=$result")
        } else {
            @Suppress("DEPRECATION")
            val result = am.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            NovaLog.d(TAG, "requestAudioFocus() (legacy) result=$result")
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusListener)
        }
    }

    fun init() {
        NovaLog.d(TAG, "init() вызван")
        tts = TextToSpeech(context) { status ->
            isReady = (status == TextToSpeech.SUCCESS)
            NovaLog.d(TAG, "Init status: $status, ready=$isReady")

            if (isReady) {
                tts?.language = Locale.getDefault()

                // Явно MEDIA/SPEECH — иначе движок может идти через ASSISTANT/notification
                // path, и при параллельном silent AudioTrack (MEDIA) первые мс теряются.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }

                val engines = tts?.engines
                NovaLog.d(TAG, "Доступные движки в системе: ${engines?.map { it.label }}")

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        NovaLog.d(TAG, "onStart: $utteranceId")
                    }
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != currentUtteranceId) {
                            NovaLog.d(TAG, "onDone устаревшей фразы игнорирован: $utteranceId")
                            return
                        }
                        currentUtteranceId = null
                        NovaLog.d(TAG, "onDone: $utteranceId")
                        onDone?.invoke()
                    }
                    override fun onError(utteranceId: String?) {
                        if (utteranceId != currentUtteranceId) {
                            NovaLog.d(TAG, "onError устаревшей фразы игнорирован: $utteranceId")
                            return
                        }
                        currentUtteranceId = null
                        NovaLog.e(TAG, "onError: $utteranceId")
                        onError?.invoke("Ошибка воспроизведения TTS")
                    }
                    // onRangeStart — подсветка слов в reader.html (window.onTTSWordRange)
                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        onWordRange?.invoke(start, end)
                    }
                })
            } else {
                NovaLog.e(TAG, "Инициализация TTS провалилась, status=$status")
                onError?.invoke("Не удалось инициализировать TTS (status=$status)")
            }

            // Пользователь может нажать Play сразу после открытия книги, пока
            // TextToSpeech ещё создаётся асинхронно. Не теряем первую фразу:
            // она будет отправлена сразу после успешного init.
            val queued = pendingText
            if (isReady && !queued.isNullOrBlank()) {
                pendingText = null
                NovaLog.d(TAG, "TTS готов — запускаем отложенную первую фразу")
                speak(queued)
            }
        }
    }

    fun speak(text: String) {
        NovaLog.d(TAG, "speak() вызван, длина текста=${text.length}")
        if (!isReady) {
            NovaLog.w(TAG, "speak(): TTS ещё не готов — фраза поставлена в очередь")
            pendingText = text
            return
        }
        lastText = text
        pausedForInterruption = false
        requestAudioFocus()
        val utteranceId = UUID.randomUUID().toString()
        currentUtteranceId = utteranceId
        val params = lookaheadJson?.let { android.os.Bundle().apply { putString("nova_prefetch", it) } }
        lookaheadJson = null
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        NovaLog.d(TAG, "speak() result=$result (SUCCESS=${TextToSpeech.SUCCESS}, ERROR=${TextToSpeech.ERROR}), utteranceId=$utteranceId")
        if (result == TextToSpeech.ERROR) {
            currentUtteranceId = null
            NovaLog.e(TAG, "speak() вернул ERROR")
            onError?.invoke("Ошибка воспроизведения")
        }
    }

    fun setRate(rate: Float) {
        NovaLog.d(TAG, "setRate($rate)")
        tts?.setSpeechRate(rate)
    }

    /** Окончательная остановка — по команде пользователя (стоп/пауза/смена
     *  книги), а не из-за временного звонка. Отдаёт аудиофокус насовсем:
     *  если он вернётся, речь сама уже не продолжится (в отличие от
     *  LOSS_TRANSIENT в audioFocusListener). */
    fun stop() {
        NovaLog.d(TAG, "stop()")
        pendingText = null
        pausedForInterruption = false
        lastText = null
        currentUtteranceId = null
        tts?.stop()
        abandonAudioFocus()
    }

    /** У Android TTS нет настоящей паузы с возможностью продолжить с той же
     *  фразы — стоп + запоминание позиции остаются на стороне JS (text-walker.js). */
    fun pause() = stop()

    /** Пересоздаёт TextToSpeech — например, если пользователь сменил
     *  системный движок в настройках Android прямо во время работы приложения. */
    fun recreate() {
        NovaLog.d(TAG, "recreate() — пересоздаём TTS")
        tts?.shutdown()
        isReady = false
        init()
    }

    fun destroy() {
        NovaLog.d(TAG, "destroy()")
        pendingText = null
        currentUtteranceId = null
        abandonAudioFocus()
        tts?.shutdown()
    }
}

