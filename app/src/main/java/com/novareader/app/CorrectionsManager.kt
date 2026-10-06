package com.novareader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Глобальные и книжные коррекции произношения TTS.
 *
 * Формат tts_corrections.json:
 * {
 *   "global": [ { "wrong": "ИИ", "correct": "искусственный интеллект",
 *                 "case_insensitive": true }, ... ],
 *   "books": {
 *     "<relativePath>": [ { ... }, ... ],
 *     ...
 *   }
 * }
 *
 * Ключ книги — relativePath (тот же, что в LibraryManager), а не абсолютный
 * путь: booksDir может меняться между установками.
 */
class CorrectionsManager(private val context: Context) {

    private val file = NovaStorage.ttsCorrectionsJsonFile(context)
    private val lock = Any()

    // ── загрузка / сохранение ─────────────────────────────────────────

    private fun load(): JSONObject = synchronized(lock) {
        if (file.exists()) {
            try {
                JSONObject(file.readText())
            } catch (e: Exception) {
                NovaLog.e("NovaReader.TTS", "tts_corrections.json повреждён: ${e.message}")
                try {
                    val backup = File(file.parentFile, "tts_corrections.json.corrupt-${System.currentTimeMillis()}")
                    file.copyTo(backup, overwrite = true)
                } catch (_: Exception) {}
                JSONObject()
            }
        } else JSONObject()
    }

    private fun save(obj: JSONObject) = synchronized(lock) {
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(obj.toString())
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(obj.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            NovaLog.e("NovaReader.TTS", "Не удалось записать tts_corrections.json: ${e.message}")
        }
    }

    // ── глобальные ─────────────────────────────────────────────────────

    fun getGlobal(): List<CorrectionEntry> {
        val obj = load()
        return parseList(obj.optJSONArray("global"))
    }

    fun setGlobal(list: List<CorrectionEntry>) {
        val obj = load()
        obj.put("global", serializeList(list))
        save(obj)
    }

    // ── книжные ────────────────────────────────────────────────────────

    fun getForBook(relativePath: String): List<CorrectionEntry> {
        if (relativePath.isBlank()) return emptyList()
        val obj = load()
        val books = obj.optJSONObject("books") ?: return emptyList()
        return parseList(books.optJSONArray(relativePath))
    }

    fun setForBook(relativePath: String, list: List<CorrectionEntry>) {
        if (relativePath.isBlank()) return
        val obj = load()
        val books = obj.optJSONObject("books") ?: JSONObject().also { obj.put("books", it) }
        if (list.isEmpty()) books.remove(relativePath)
        else books.put(relativePath, serializeList(list))
        save(obj)
    }

    /** Объединённый список: книжные + глобальные (для отправки в JS). */
    fun getCombined(relativePath: String?): List<CorrectionEntry> {
        val book = if (relativePath.isNullOrBlank()) emptyList() else getForBook(relativePath)
        return book + getGlobal()
    }

    // ── утилиты ────────────────────────────────────────────────────────

    private fun parseList(arr: JSONArray?): List<CorrectionEntry> {
        if (arr == null) return emptyList()
        val out = ArrayList<CorrectionEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val wrong = o.optString("wrong", "").trim()
            if (wrong.isEmpty()) continue
            out.add(CorrectionEntry(
                wrong = wrong,
                correct = o.optString("correct", "").trim(),
                caseInsensitive = o.optBoolean("case_insensitive", true),
            ))
        }
        return out
    }

    private fun serializeList(list: List<CorrectionEntry>): JSONArray {
        val arr = JSONArray()
        for (e in list) {
            if (e.wrong.isBlank()) continue
            arr.put(JSONObject().apply {
                put("wrong", e.wrong)
                put("correct", e.correct)
                put("case_insensitive", e.caseInsensitive)
            })
        }
        return arr
    }
}

data class CorrectionEntry(
    val wrong: String,
    val correct: String,
    val caseInsensitive: Boolean = true,
)