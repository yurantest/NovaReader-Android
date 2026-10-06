package com.novareader.app

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Локальная библиотека книг — Android-эквивалент desktop config.py
 * (library.json + positions.json), без Room/SQLite.
 *
 * MOBI/AZW3-метаданные (title/author) извлекаются через MobiParser —
 * порт логики foliate-js/mobi.js, который умеет переключаться на
 * KF8-заголовок и фильтровать служебные значения вроде "EBOK".
 *
 * Обложка MOBI/AZW3 извлекается отдельно — через MobiParser.extractCoverBytes,
 * который работает строго по первому MOBI-заголовку (resourceStart +
 * coverOffset/thumbnailOffset из одного и того же EXTH). Именно эта
 * связка давала правильную обложку для книг Эксмо/АСТ.
 *
 * Для EPUB/FB2 — собственные парсеры (ZipFile/XML).
 */
class LibraryManager(private val context: Context) {

    private val libraryFile = NovaStorage.libraryJsonFile(context)
    private val positionsFile = NovaStorage.positionsJsonFile(context)
    private val coversDir = NovaStorage.coversDir(context)

    private val lock = Any()
    private val ioLock = Any()

    // ── низкоуровневый доступ к JSON-файлам ─────────────────────────────

    private fun loadLibrary(): JSONArray = synchronized(ioLock) {
        if (libraryFile.exists()) {
            try {
                JSONArray(libraryFile.readText())
            } catch (e: Exception) {
                NovaLog.e("NovaReader.Library", "library.json повреждён, начинаем заново: ${e.message}")
                try {
                    val backup = File(libraryFile.parentFile, "library.json.corrupt-${System.currentTimeMillis()}")
                    libraryFile.copyTo(backup, overwrite = true)
                } catch (_: Exception) {}
                JSONArray()
            }
        } else JSONArray()
    }

    private fun saveLibrary(arr: JSONArray) = synchronized(ioLock) {
        writeAtomically(libraryFile, arr.toString())
    }

    private fun loadPositions(): JSONObject = synchronized(ioLock) {
        if (positionsFile.exists()) {
            try {
                JSONObject(positionsFile.readText())
            } catch (e: Exception) {
                NovaLog.e("NovaReader.Library", "positions.json повреждён, начинаем заново: ${e.message}")
                try {
                    val backup = File(positionsFile.parentFile, "positions.json.corrupt-${System.currentTimeMillis()}")
                    positionsFile.copyTo(backup, overwrite = true)
                } catch (_: Exception) {}
                JSONObject()
            }
        } else JSONObject()
    }

    private fun savePositions(obj: JSONObject) = synchronized(ioLock) {
        writeAtomically(positionsFile, obj.toString())
    }

    private fun writeAtomically(target: File, content: String) {
        try {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        } catch (e: Exception) {
            NovaLog.e("NovaReader.Library", "Не удалось записать ${target.name}: ${e.message}")
        }
    }

    private fun bookKey(title: String, author: String): String {
        val norm = { s: String ->
            Normalizer.normalize(s.trim().lowercase(Locale.getDefault()), Normalizer.Form.NFC)
                .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        }
        return "${norm(title)}|${norm(author.ifBlank { "неизвестен" })}"
    }

    // ── Каталог книг ─────────────────────────────────────────────────

    fun addBook(relativePath: String, displayName: String, format: String): JSONObject {
        val bookFile = File(NovaStorage.booksDir(context), relativePath)

        // MOBI/AZW3 — особая ветка: метаданные из MobiParser.extract,
        // обложка из MobiParser.extractCoverBytes.
        if (format == "mobi" || format == "azw3") {
            return addMobiBook(bookFile, relativePath, displayName, format)
        }

        val meta = extractMetadata(bookFile, format, displayName)
        val title = meta.optString("title", displayName)
        val author = meta.optString("author", "Неизвестен")
        val key = bookKey(title, author)
        val coverPath = extractCover(bookFile, format, relativePath)

        synchronized(lock) {
            val library = loadLibrary()
            for (i in 0 until library.length()) {
                val book = library.getJSONObject(i)
                if (bookKey(book.optString("title"), book.optString("author")) == key) {
                    val formats = book.optJSONObject("formats") ?: JSONObject().also { book.put("formats", it) }
                    formats.put(format, relativePath)
                    if (title.isNotBlank()) book.put("title", title)
                    if (author.isNotBlank()) book.put("author", author)
                    if (book.optString("cover_path").isBlank() && coverPath != null) {
                        book.put("cover_path", coverPath)
                    }
                    book.put("last_modified", System.currentTimeMillis())
                    saveLibrary(library)
                    return book
                }
            }

            val record = JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("title", title)
                put("author", author)
                put("series", meta.opt("series") ?: JSONObject.NULL)
                put("cover_path", coverPath ?: "")
                put("formats", JSONObject().put(format, relativePath))
                put("added", System.currentTimeMillis())
                put("last_modified", System.currentTimeMillis())
            }
            library.put(record)
            saveLibrary(library)
            return record
        }
    }

    /**
     * Ветка для MOBI/AZW3.
     *
     * Метаданные берём из MobiParser.extract() — там title/author уже
     * очищены от мусора ("EBOK", "2400x38", обрывки) и корректно
     * извлечены из KF8-EXTH.
     *
     * Обложку берём из MobiParser.extractCoverBytes(file) — этот метод
     * использует ровно ту же логику, что работала в самой первой версии
     * LibraryManager: resourceStart + coverOffset/thumbnailOffset
     * из ПЕРВОГО MOBI-заголовка, без переключения на KF8. Именно эта
     * связка давала правильные обложки для книг Эксмо/АСТ.
     */
    private fun addMobiBook(
        bookFile: File,
        relativePath: String,
        displayName: String,
        format: String
    ): JSONObject {
        val mobi = try {
            MobiParser.extract(bookFile, displayName)
        } catch (e: Exception) {
            NovaLog.e("NovaReader.Library", "MobiParser упал на $displayName: ${e.message}")
            MobiParser.MobiMetadata(
                title = displayName.substringBeforeLast('.'),
                authors = emptyList(),
                language = null,
            )
        }

        val title = mobi.title?.takeIf { it.isNotBlank() }
            ?: displayName.substringBeforeLast('.').takeIf { it.isNotBlank() }
            ?: displayName
        val author = mobi.authors.joinToString(", ").ifBlank { "Неизвестен" }
        val key = bookKey(title, author)

        // Обложка — строго по старому алгоритму (одна запись = один EXTH).
        val coverPath: String? = try {
            val bytes = MobiParser.extractCoverBytes(bookFile)
            if (bytes != null && bytes.isNotEmpty() &&
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) {
                val coverFile = File(coversDir, "${UUID.randomUUID()}.jpg")
                coverFile.writeBytes(bytes)
                "covers/${coverFile.name}"
            } else null
        } catch (e: Exception) {
            NovaLog.e("NovaReader.Library", "MOBI-обложка не извлеклась: ${e.message}")
            null
        }

        synchronized(lock) {
            val library = loadLibrary()
            for (i in 0 until library.length()) {
                val book = library.getJSONObject(i)
                if (bookKey(book.optString("title"), book.optString("author")) == key) {
                    val formats = book.optJSONObject("formats") ?: JSONObject().also { book.put("formats", it) }
                    formats.put(format, relativePath)
                    if (title.isNotBlank()) book.put("title", title)
                    if (author.isNotBlank()) book.put("author", author)
                    if (book.optString("cover_path").isBlank() && coverPath != null) {
                        book.put("cover_path", coverPath)
                    }
                    book.put("last_modified", System.currentTimeMillis())
                    saveLibrary(library)
                    return book
                }
            }

            val record = JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("title", title)
                put("author", author)
                put("series", JSONObject.NULL)
                put("cover_path", coverPath ?: "")
                put("formats", JSONObject().put(format, relativePath))
                put("added", System.currentTimeMillis())
                put("last_modified", System.currentTimeMillis())
            }
            library.put(record)
            saveLibrary(library)
            return record
        }
    }

    fun removeBook(bookId: String) {
        synchronized(lock) {
            val library = loadLibrary()
            val positions = loadPositions()
            val kept = JSONArray()
            for (i in 0 until library.length()) {
                val book = library.getJSONObject(i)
                if (book.optString("id") == bookId) {
                    val formats = book.optJSONObject("formats")
                    formats?.keys()?.forEach { fmt -> positions.remove(formats.getString(fmt)) }
                    val cp = book.optString("cover_path")
                    if (cp.isNotBlank()) File(NovaStorage.rootDir(context), cp).delete()
                    formats?.keys()?.forEach { fmt ->
                        File(NovaStorage.booksDir(context), formats.getString(fmt)).delete()
                    }
                } else {
                    kept.put(book)
                }
            }
            saveLibrary(kept)
            savePositions(positions)
        }
    }

    fun getBooks(): String {
        synchronized(lock) {
            val library = loadLibrary()
            val positions = loadPositions()
            val result = JSONArray()
            for (i in 0 until library.length()) {
                val book = library.getJSONObject(i)
                val formats = book.optJSONObject("formats") ?: JSONObject()
                if (formats.length() == 0) continue
                val primaryFormat = formats.keys().next()
                val primaryPath = formats.getString(primaryFormat)
                val pos = positions.optJSONObject(primaryPath)

                result.put(JSONObject().apply {
                    put("id", book.optString("id"))
                    put("title", book.optString("title"))
                    put("author", book.optString("author"))
                    put("cover_path", book.optString("cover_path"))
                    put("format", primaryFormat)
                    put("relative_path", primaryPath)
                    put("progress", pos?.optDouble("progress", 0.0) ?: 0.0)
                    put("last_read", pos?.optLong("last_read", 0L) ?: 0L)
                })
            }
            return result.toString()
        }
    }

    fun getCoverFile(coverRelativePath: String): File = File(NovaStorage.rootDir(context), coverRelativePath)

    fun getBookFile(relativePath: String): File = File(NovaStorage.booksDir(context), relativePath)

    // ── Прогресс ────────────────────────────────────────────────────────

    fun getPosition(relativePath: String): String {
        synchronized(lock) {
            val positions = loadPositions()
            val pos = positions.optJSONObject(relativePath) ?: return "null"
            return JSONObject().apply {
                put("progress", pos.optDouble("progress", 0.0))
                put("position", pos.opt("position") ?: JSONObject.NULL)
            }.toString()
        }
    }

    fun savePosition(relativePath: String, positionJson: String, progress: Double) {
        if (relativePath.isBlank()) return
        synchronized(lock) {
            val positions = loadPositions()
            val entry = positions.optJSONObject(relativePath) ?: JSONObject().also { positions.put(relativePath, it) }
            entry.put("progress", progress)
            entry.put("position", try { JSONObject(positionJson) } catch (_: Exception) { JSONObject() })
            entry.put("last_read", System.currentTimeMillis())
            savePositions(positions)
        }
    }

    // ── Метаданные EPUB / FB2 ──────────────────────────────────────────

    private fun extractMetadata(file: File, format: String, fallbackTitle: String): JSONObject {
        return try {
            when (format) {
                "epub" -> extractEpubMetadata(file, fallbackTitle)
                "fb2" -> extractFb2Metadata(file, fallbackTitle)
                else -> JSONObject().put("title", fallbackTitle.substringBeforeLast('.'))
            }
        } catch (e: Exception) {
            NovaLog.e("NovaReader.Library", "Не удалось извлечь метаданные: ${e.message}")
            JSONObject().put("title", fallbackTitle.substringBeforeLast('.'))
        }
    }

    private fun extractEpubMetadata(file: File, fallbackTitle: String): JSONObject {
        ZipFile(file).use { zip ->
            val opfEntry = zip.entries().asSequence().firstOrNull { it.name.endsWith(".opf") }
                ?: return JSONObject().put("title", fallbackTitle.substringBeforeLast('.'))
            val opfText = zip.getInputStream(opfEntry).bufferedReader().readText()
            val title = Regex("<dc:title[^>]*>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL)
                .find(opfText)?.groupValues?.get(1)?.trim()?.let(::unescapeXml)
                ?: fallbackTitle.substringBeforeLast('.')
            val authors = Regex("<dc:creator[^>]*>(.*?)</dc:creator>", RegexOption.DOT_MATCHES_ALL)
                .findAll(opfText)
                .map { it.groupValues[1].trim().let(::unescapeXml) }
                .filter { it.isNotBlank() }
                .toList()
            val author = authors.joinToString(", ").ifBlank { "Неизвестен" }
            return JSONObject().put("title", title).put("author", author)
        }
    }

    private fun extractFb2Metadata(file: File, fallbackTitle: String): JSONObject {
        val text = file.inputStream().use { input ->
            val buffer = ByteArray(64_000)
            val read = input.read(buffer)
            String(buffer, 0, if (read > 0) read else 0, Charsets.UTF_8)
        }
        val title = Regex("<book-title>(.*?)</book-title>", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)?.trim()?.let(::unescapeXml)
            ?: fallbackTitle.substringBeforeLast('.')
        val first = Regex("<first-name>(.*?)</first-name>").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val last = Regex("<last-name>(.*?)</last-name>").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val author = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Неизвестен" }
        return JSONObject().put("title", title).put("author", unescapeXml(author))
    }

    private fun unescapeXml(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")

    // ── Обложки EPUB / FB2 ──────────────────────────────────────────────

    private fun extractCover(file: File, format: String, relativePath: String): String? {
        val bytes = try {
            when (format) {
                "epub" -> extractEpubCoverBytes(file)
                "fb2" -> extractFb2CoverBytes(file)
                else -> null
            }
        } catch (e: Exception) {
            NovaLog.e("NovaReader.Library", "Не удалось извлечь обложку: ${e.message}")
            null
        } ?: return null

        if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) return null

        val coverFile = File(coversDir, "${UUID.randomUUID()}.jpg")
        coverFile.writeBytes(bytes)
        return "covers/${coverFile.name}"
    }

    private fun extractEpubCoverBytes(file: File): ByteArray? {
        ZipFile(file).use { zip ->
            val opfEntry = zip.entries().asSequence().firstOrNull { it.name.endsWith(".opf") } ?: return null
            val opfPath = opfEntry.name
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opfText = zip.getInputStream(opfEntry).bufferedReader().readText()

            val coverId = Regex("""<meta[^>]+name="cover"[^>]+content="([^"]+)"""").find(opfText)?.groupValues?.get(1)
            var href: String? = null
            if (coverId != null) {
                href = Regex("""<item[^>]+id="$coverId"[^>]+href="([^"]+)"""").find(opfText)?.groupValues?.get(1)
                    ?: Regex("""<item[^>]+href="([^"]+)"[^>]+id="$coverId"""").find(opfText)?.groupValues?.get(1)
            }
            if (href == null) {
                href = Regex("""<item[^>]+properties="cover-image"[^>]+href="([^"]+)"""").find(opfText)?.groupValues?.get(1)
                    ?: Regex("""<item[^>]+href="([^"]+)"[^>]+properties="cover-image"""").find(opfText)?.groupValues?.get(1)
            }
            if (href == null) return null
            val coverPath = if (opfDir.isBlank()) href else "$opfDir/$href"
            val entry = zip.getEntry(coverPath) ?: zip.entries().asSequence()
                .firstOrNull { it.name.endsWith(href.substringAfterLast('/')) } ?: return null
            return zip.getInputStream(entry).readBytes()
        }
    }

    private fun extractFb2CoverBytes(file: File): ByteArray? {
        val text = file.readText(Charsets.UTF_8)
        val coverRef = Regex("""<coverpage>.*?href="#([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: return null
        val binaryMatch = Regex(
            """<binary[^>]+id="$coverRef"[^>]*>([\s\S]*?)</binary>"""
        ).find(text) ?: return null
        val base64 = binaryMatch.groupValues[1].replace("\\s".toRegex(), "")
        return try { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) } catch (_: Exception) { null }
    }
}