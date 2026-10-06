package com.novareader.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Шрифты для читалки — Android-эквивалент desktop _get_fonts_dir()/
 * _on_import_font() в settings_window.py:
 *
 * - Встроенные шрифты лежат в assets/ibc/fonts/ (аналог primary =
 *   ibc/fonts рядом с исполняемым файлом на десктопе) — только чтение.
 * - Пользовательские шрифты — в NovaStorage.rootDir()/fonts/ (аналог
 *   desktop-фолбэка ~/.config/NovaReader/fonts/), копируются туда через
 *   SAF при импорте.
 */
class FontManager(private val context: Context) {

    private val userFontsDir: File get() = File(NovaStorage.rootDir(context), "fonts").apply { mkdirs() }

    private val supportedExtensions = setOf("ttf", "otf", "woff", "woff2")

    fun getFontFaces(): String {
        val result = JSONArray()

        try {
            context.assets.list("ibc/fonts")?.forEach { fileName ->
                val ext = fileName.substringAfterLast('.', "").lowercase()
                if (ext !in supportedExtensions) return@forEach
                val info = parseAssetFont(fileName)
                // AssetsPathHandler публикует APK assets под /assets/.
                // Передаём абсолютный URL, чтобы FontFace не пытался искать
                // встроенный шрифт рядом с reader.html.
                val fileUrl = "https://appassets.androidplatform.net/assets/ibc/fonts/$fileName"
                result.put(buildEntry(info, fileName, fileUrl))
            }
        } catch (e: Exception) {
            NovaLog.e("NovaReader.FontManager", "Не удалось прочитать встроенные шрифты: ${e.message}")
        }

        userFontsDir.listFiles()?.forEach { file ->
            val ext = file.extension.lowercase()
            if (ext !in supportedExtensions) return@forEach
            mirrorToServeDir(file)
            val info = FontParser.parse(file)
            val fileUrl = "https://appassets.androidplatform.net/fonts/${file.name}"
            result.put(buildEntry(info, file.name, fileUrl))
        }

        return result.toString()
    }

    /** Копирует пользовательский шрифт в filesDir/fonts_serve/, откуда его
     *  реально отдаёт WebViewAssetLoader (InternalStoragePathHandler не
     *  может обслуживать внешнее хранилище напрямую — то же ограничение,
     *  что и для файлов книг). */
    private fun mirrorToServeDir(sourceFile: File) {
        val serveDir = File(context.filesDir, "fonts_serve").apply { mkdirs() }
        val target = File(serveDir, sourceFile.name)
        if (!target.exists() || target.length() != sourceFile.length()) {
            try {
                sourceFile.copyTo(target, overwrite = true)
            } catch (e: Exception) {
                NovaLog.e("NovaReader.FontManager", "Не удалось скопировать шрифт для отдачи: ${e.message}")
            }
        }
    }

    private fun buildEntry(info: ParsedFontInfo?, fileName: String, fileUrl: String?): JSONObject {
        val family = info?.family ?: fileName.substringBeforeLast('.')
        val weight = info?.weight ?: 400
        val style = if (info?.italic == true) "italic" else "normal"

        // Один family может состоять из нескольких отдельных файлов:
        // Regular.ttf + Bold.ttf + Italic.ttf + BoldItalic.ttf. Для браузера
        // это ОДНО семейство с разными weight/style, поэтому не прячем
        // начертания друг от друга и отдаём JS все метаданные каждого файла.
        val variantName = when {
            weight >= 800 && style == "italic" -> "$family Black Italic"
            weight >= 700 && style == "italic" -> "$family Bold Italic"
            weight >= 600 && style == "italic" -> "$family SemiBold Italic"
            weight >= 700 -> "$family Bold"
            weight >= 600 -> "$family SemiBold"
            weight >= 500 && style == "italic" -> "$family Medium Italic"
            weight >= 500 -> "$family Medium"
            style == "italic" -> "$family Italic"
            else -> family
        }

        return JSONObject().apply {
            put("family", family)
            put("unique_family", info?.uniqueFamily ?: family)
            put("display_name", variantName)
            put("file", fileName)
            if (fileUrl != null) put("file_url", fileUrl)
            put("variable", info?.variable ?: false)
            put("weight", weight)
            put("wght_min", info?.wghtMin ?: weight)
            put("wght_max", info?.wghtMax ?: weight)
            put("style", style)
        }
    }

    private fun parseAssetFont(fileName: String): ParsedFontInfo? {
        return try {
            val tmp = File(context.cacheDir, "font_probe_$fileName")
            context.assets.open("ibc/fonts/$fileName").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            val info = FontParser.parse(tmp)
            tmp.delete()
            info
        } catch (e: Exception) {
            null
        }
    }

    fun importFont(context: Context, uri: Uri, displayName: String): String? {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext !in supportedExtensions) {
            NovaLog.e("NovaReader.FontManager", "Неподдерживаемый формат шрифта: .$ext")
            return null
        }
        val cleanName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(userFontsDir, cleanName)
        if (dest.exists()) {
            NovaLog.d("NovaReader.FontManager", "Шрифт уже установлен: $cleanName")
            return cleanName
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            if (ext in setOf("ttf", "otf") && FontParser.parse(dest) == null) {
                dest.delete()
                NovaLog.e("NovaReader.FontManager", "Файл не распознан как валидный шрифт: $cleanName")
                return null
            }
            NovaLog.d("NovaReader.FontManager", "Шрифт импортирован: $cleanName")
            cleanName
        } catch (e: Exception) {
            dest.delete()
            NovaLog.e("NovaReader.FontManager", "Ошибка импорта шрифта: ${e.message}")
            null
        }
    }

    fun userFontsDirForAssetLoader(): File = userFontsDir
}
