package com.novareader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Книга, позиция чтения, закладки, выделения, заметки для ТЕКУЩЕЙ открытой
 * книги. Каталог и прогресс хранятся в LibraryManager (library.json /
 * positions.json, ключ — relativePath) — здесь только текущая книга и её
 * закладки/выделения/заметки, отдельным JSON-файлом на relativePath.
 *
 * Формат данных на диске (book_data/<safe>.json в NovaStorage.rootDir())
 * сознательно
 * похож на desktop config.py (bookmarks/highlights/notes как массивы
 * объектов), чтобы reader.html не пришлось переучивать под другую схему.
 */
class BookManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("novareader_book", Context.MODE_PRIVATE)
    private val library = LibraryManager(context)

    private fun currentRelativePath(): String? = prefs.getString("current_book_path", null)

    /** Ключ для book_data — сам relativePath (имя файла книги, как
     *  self._r.current_book на десктопе — жёсткий путь, без UUID и
     *  прослойки в виде отдельного id). relativePath теперь стабилен
     *  (LibraryActivity.importBook больше не добавляет timestamp при
     *  повторном импорте того же файла), поэтому ключевать данные прямо
     *  по нему безопасно и надёжно, как на десктопе. */
    private fun currentBookDataKey(): String? = currentRelativePath()

    private fun dataFile(key: String) =
        java.io.File(NovaStorage.bookDataDir(context), "${key.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    private fun loadData(key: String): JSONObject {
        val f = dataFile(key)
        return if (f.exists()) JSONObject(f.readText()) else JSONObject()
    }

    private fun saveData(key: String, obj: JSONObject) {
        dataFile(key).writeText(obj.toString())
    }

    fun setCurrentBook(relativePath: String, displayName: String, type: String) {
        // Заносим/обновляем книгу в каталоге библиотеки — то же самое, что
        // add_book() в desktop config.py делает при импорте.
        try {
            library.addBook(relativePath, displayName, type)
        } catch (e: Exception) {
            NovaLog.e("NovaReader.BookManager", "Не удалось добавить книгу в библиотеку: ${e.message}")
        }
        prefs.edit()
            .putString("current_book_path", relativePath)
            .putString("current_book_name", displayName)
            .putString("current_book_type", type)
            .apply()
    }

    /** Открыть книгу, которая уже есть в библиотеке (relativePath уже существует
     *  в filesDir/books/) — используется экраном библиотеки, без повторного
     *  копирования файла через SAF. */
    fun openExistingBook(relativePath: String, displayName: String, type: String) {
        prefs.edit()
            .putString("current_book_path", relativePath)
            .putString("current_book_name", displayName)
            .putString("current_book_type", type)
            .apply()
    }

    fun getBookData(): String {
        val relPath = currentRelativePath()
        val name = prefs.getString("current_book_name", "") ?: ""
        val type = prefs.getString("current_book_type", "unknown") ?: "unknown"

        // Защита от "фантомной" книги: SharedPreferences в редких случаях
        // переживают удаление приложения (восстановление из бэкапа на
        // некоторых прошивках), тогда как сам файл книги в filesDir/books/
        // — нет. Раз файла физически нет, книги для нас нет вообще, точно
        // как на десктопе (там self._r.current_book — реальный путь, и
        // если файла по нему нет, книга просто не открывается).
        val bookFile = if (relPath != null) java.io.File(NovaStorage.booksDir(context), relPath) else null
        if (relPath == null || bookFile == null || !bookFile.exists()) {
            if (relPath != null) {
                NovaLog.d("NovaReader.BookManager", "Файл текущей книги не найден на диске ($relPath) — сбрасываем как будто книги нет")
                prefs.edit().remove("current_book_path").remove("current_book_name").remove("current_book_type").apply()
            }
            return JSONObject().apply {
                put("type", "unknown")
                put("name", "")
                put("file_url", "")
                put("path", "")
            }.toString()
        }

        // Книга отдаётся через WebViewAssetLoader (InternalStoragePathHandler
        // на "/books/" в MainActivity), а не file:// — так работает fetch()
        // внутри loadBook() в reader.html.
        val fileUrl = "https://appassets.androidplatform.net/books/$relPath"

        return JSONObject().apply {
            put("type", type)
            put("name", name)
            put("file_url", fileUrl)
            put("path", relPath)
        }.toString()
    }

    // ── Позиция чтения — делегируем в LibraryManager (positions.json) ───

    fun getPosition(): String {
        val relPath = currentRelativePath() ?: return "null"
        return library.getPosition(relPath)
    }

    fun savePosition(positionJson: String, progress: Float) {
        val relPath = currentRelativePath() ?: return
        library.savePosition(relPath, positionJson, progress.toDouble())
    }

    // ── Закладки/выделения/заметки — свой файл на книгу ─────────────────

    private fun getArray(key: String): JSONArray {
        val relPath = currentRelativePath() ?: return JSONArray()
        val data = loadData(relPath)
        return data.optJSONArray(key) ?: JSONArray()
    }

    private fun mutateArray(key: String, block: (JSONArray) -> Unit) {
        val relPath = currentRelativePath() ?: return
        val data = loadData(relPath)
        val arr = data.optJSONArray(key) ?: JSONArray().also { data.put(key, it) }
        block(arr)
        saveData(relPath, data)
    }

    private fun removeById(key: String, id: String) {
        val relPath = currentRelativePath() ?: return
        val data = loadData(relPath)
        val arr = data.optJSONArray(key) ?: return
        val filtered = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.optString("id") != id) filtered.put(item)
        }
        data.put(key, filtered)
        saveData(relPath, data)
    }

    fun getBookmarks(): String = getArray("bookmarks").toString()

    fun saveBookmark(label: String, progress: Float, positionJson: String) {
        mutateArray("bookmarks") { arr ->
            arr.put(JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("label", label)
                put("progress", progress)
                put("position", try { JSONObject(positionJson) } catch (_: Exception) { JSONObject() })
                put("timestamp", System.currentTimeMillis())
            })
        }
    }

    fun removeBookmark(bookmarkId: String) = removeById("bookmarks", bookmarkId)

    fun getHighlights(): String = getArray("highlights").toString()

    fun saveHighlight(text: String, color: String, style: String, cfi: String) {
        // ВАЖНО: cfi здесь — не голая CFI-строка, а JSON-блок вида
        // {id, section, text, cfi}, который формирует createUserHighlight()
        // в reader.html. Раньше этот блок "расплющивался" прямо в entry
        // (entry.cfi заменялся на extra.cfi — голую CFI-строку), из-за чего
        // на диск сохранялось {id, text, color, style, section, cfi:"epubcfi(...)"}.
        // А applyHighlightsForCurrentSection() в bookmarks-highlights.js
        // делает JSON.parse(h.cfi), ожидая ИМЕННО исходный блок целиком —
        // ровно как на ПК (см. reader_window.py: saveHighlight хранит cfi
        // as-is, не разбирая). JSON.parse на голой CFI-строке падал молча
        // (try/catch), поэтому getHighlights() отдавал непустой массив, но
        // ни одна подсветка не применялась после перезапуска — книга
        // выглядела "чистой", хотя данные на диске были.
        val cfiData = try { JSONObject(cfi) } catch (_: Exception) { JSONObject() }
        val id = cfiData.optString("id").ifBlank { UUID.randomUUID().toString() }
        mutateArray("highlights") { arr ->
            arr.put(JSONObject().apply {
                put("id", id)
                put("text", text)
                put("color", color)
                put("style", style)
                put("cfi", cfi) // храним целиком, как на ПК — не расплющиваем
                put("timestamp", System.currentTimeMillis())
            })
        }
    }

    fun removeHighlight(highlightId: String) = removeById("highlights", highlightId)

    fun getNotes(): String = getArray("notes").toString()

    fun saveNote(highlightId: String, noteText: String, cfiJson: String) {
        mutateArray("notes") { arr ->
            arr.put(JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("highlightId", highlightId)
                put("text", noteText)
                put("cfi", cfiJson)
            })
        }
    }

    fun removeNote(noteId: String) = removeById("notes", noteId)

    fun updateNote(noteId: String, newText: String) {
        val relPath = currentRelativePath() ?: return
        val data = loadData(relPath)
        val arr = data.optJSONArray("notes") ?: return
        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i)
            if (n.optString("id") == noteId) n.put("text", newText)
        }
        saveData(relPath, data)
    }

    fun saveQuoteImage(base64Data: String) {
        try {
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            val resolver = context.contentResolver
            val fileName = "quote_${System.currentTimeMillis()}.png"

            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Scoped storage (Android 10+): пишем через MediaStore прямо в
                // публичную галерею — файл сразу виден в Галерее/Google Фото
                // и его можно расшарить, без разрешений на запись во внешнее
                // хранилище (они больше не работают так на новых Android).
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NovaReader")
                }
                resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
                    "NovaReader"
                ).apply { mkdirs() }
                val file = java.io.File(picturesDir, fileName)
                android.net.Uri.fromFile(file)
            }

            if (uri == null) {
                NovaLog.e("NovaReader.BookManager", "Не удалось создать файл цитаты через MediaStore")
                return
            }

            resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                ?: run {
                    // Фолбэк для пути до Android 10 (Uri.fromFile не открывается через resolver).
                    java.io.File(uri.path!!).writeBytes(bytes)
                }

            NovaLog.d("NovaReader.BookManager", "Цитата сохранена в галерею: $uri")
        } catch (e: Exception) {
            NovaLog.e("NovaReader.BookManager", "Не удалось сохранить цитату: ${e.message}")
        }
    }
}
