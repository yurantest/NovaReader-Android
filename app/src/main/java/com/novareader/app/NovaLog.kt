package com.novareader.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.LinkedBlockingDeque

/**
 * Обычный Logcat недоступен, когда приложение тестируется не из Android
 * Studio, а собранным APK прямо на телефоне. NovaLog решает это:
 *   - как и раньше, всё уходит в Logcat (android.util.Log) — ничего не потеряли
 *   - плюс копия каждой строки хранится в памяти (последние 500 строк) —
 *     можно открыть экран "Логи" в приложении и сфотографировать
 *   - плюс всё дублируется в файл на телефоне — можно вытащить через
 *     файловый менеджер или сразу отправить через "Поделиться"
 *
 * Использование: NovaLog.init(context) один раз при старте (см.
 * MainActivity.onCreate), дальше просто NovaLog.d(tag, msg) вместо Log.d.
 */
object NovaLog {
    private const val MAX_LINES = 500
    private val buffer = LinkedBlockingDeque<String>(MAX_LINES)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var logFile: File? = null

    fun init(context: Context) {
        // getExternalFilesDir — папка приложения на внешнем хранилище,
        // видна через файловый менеджер по пути
        // Android/data/com.novareader.app/files/, без специальных разрешений.
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(dir, "novareader_log.txt")
        d("NovaLog", "=== Лог начат, файл: ${logFile?.absolutePath} ===")
    }

    fun d(tag: String, msg: String) = write("D", tag, msg).also { Log.d(tag, msg) }
    fun w(tag: String, msg: String) = write("W", tag, msg).also { Log.w(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable? = null) =
        write("E", tag, msg + (tr?.let { " | ${it.message}" } ?: "")).also { Log.e(tag, msg, tr) }

    private fun write(level: String, tag: String, msg: String) {
        val line = "${timeFmt.format(java.util.Date())} $level/$tag: $msg"
        if (buffer.size >= MAX_LINES) buffer.pollFirst()
        buffer.offerLast(line)

        // Файловый I/O — синхронно, но это простые текстовые строки, не критично
        try {
            logFile?.let { f ->
                FileOutputStream(f, true).use { it.write((line + "\n").toByteArray()) }
            }
        } catch (e: Exception) {
            Log.e("NovaLog", "Не удалось записать в файл лога: ${e.message}")
        }
    }

    fun getBufferText(): String = buffer.joinToString("\n")

    fun getLogFile(): File? = logFile

    fun clear() {
        buffer.clear()
        logFile?.delete()
    }
}
