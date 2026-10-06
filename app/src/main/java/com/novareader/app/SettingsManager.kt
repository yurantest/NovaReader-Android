package com.novareader.app

import android.content.Context
import java.util.Locale
import org.json.JSONObject

/**
 * Настройки приложения. Хранятся в SharedPreferences как key/value,
 * bridge.getSettings() отдаёт их в reader.html одним JSON-объектом.
 */
class SettingsManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("novareader_settings", Context.MODE_PRIVATE)

    fun getTheme(): String = JSONObject().apply {
        put("bg", "#1a1a1a")
        put("text", "#e0e0e0")
    }.toString()

    fun getLanguage(): String = Locale.getDefault().language

    fun getSettings(): String {
        val obj = JSONObject()
        for ((key, value) in prefs.all) {
            when (value) {
                is Boolean -> obj.put(key, value)
                is Float -> obj.put(key, value.toDouble())
                is Int -> obj.put(key, value)
                is Long -> obj.put(key, value)
                is String -> obj.put(key, value)
                else -> obj.put(key, value?.toString() ?: JSONObject.NULL)
            }
        }
        return obj.toString()
    }

    fun saveSetting(keyValueJson: String) {
        try {
            val obj = JSONObject(keyValueJson)
            val key = obj.getString("key")
            val editor = prefs.edit()
            when (val value = obj.get("value")) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
                else -> editor.putString(key, value.toString())
            }
            editor.apply()
        } catch (e: Exception) {
            NovaLog.e("NovaReader.Settings", "Не удалось сохранить настройку: ${e.message}")
        }
    }

    fun getTTSCorrections(): String = prefs.getString("tts_corrections", "{}") ?: "{}"

    fun saveTTSCorrections(correctionsJson: String) {
        prefs.edit().putString("tts_corrections", correctionsJson).apply()
    }

    // ── Режим разработчика ──────────────────────────────────────────────
    var devMode: Boolean
        get() = prefs.getBoolean("dev_mode", false)
        set(value) = prefs.edit().putBoolean("dev_mode", value).apply()

    // ── "Кофеин": не гасить экран во время озвучки ──────────────────────
    var ttsScreenWakeMinutes: Int
        get() = prefs.getInt("tts_screen_wake_minutes", 0)
        set(value) = prefs.edit().putInt("tts_screen_wake_minutes", value).apply()

    // ── Поиск книг в интернете ──────────────────────────────────────────
    var bookSearchEnabled: Boolean
        get() = prefs.getBoolean("book_search_enabled", false)
        set(value) = prefs.edit().putBoolean("book_search_enabled", value).apply()

    fun getBookSearchFlibustaDomain(): String =
        prefs.getString("search_flibusta_domain", "") ?: ""

    fun setBookSearchFlibustaDomain(value: String) {
        prefs.edit().putString("search_flibusta_domain", value.trim()).apply()
    }

    fun getBookSearchSearchFloorDomain(): String =
        prefs.getString("search_searchfloor_domain", "") ?: ""

    fun setBookSearchSearchFloorDomain(value: String) {
        prefs.edit().putString("search_searchfloor_domain", value.trim()).apply()
    }

    var bookSearchLimit: Int
        get() = prefs.getInt("search_limit", 80)
        set(value) = prefs.edit().putInt("search_limit", value).apply()

    var bookSearchMaxPages: Int
        get() = prefs.getInt("search_max_pages", 3)
        set(value) = prefs.edit().putInt("search_max_pages", value).apply()

    // ── Папка для скачивания книг (SAF tree URI) ────────────────────────
    // Первое скачивание — пользователь выбирает папку через системный
    // диалог (ACTION_OPEN_DOCUMENT_TREE), URI папки сохраняется сюда.
    // Все последующие скачивания идут в ту же папку молча, без диалога.
    // Если URI пустой или права на дерево потеряны (например, после
    // переустановки/сброса) — SearchActivity снова откроет выбор папки.

    fun getDownloadFolderUri(): String =
        prefs.getString("download_folder_uri", "") ?: ""

    fun setDownloadFolderUri(uri: String) {
        prefs.edit().putString("download_folder_uri", uri).apply()
    }

    fun clearDownloadFolderUri() {
        prefs.edit().remove("download_folder_uri").apply()
    }
}