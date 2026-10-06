package com.novareader.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Полная пользовательская резервная копия NovaReader.
 *
 * В архив попадает всё, что относится к данным NovaReader:
 * - настройки SharedPreferences;
 * - каталог и позиции чтения;
 * - TTS-коррекции;
 * - закладки/выделения/заметки (book_data);
 * - обложки;
 * - все импортированные книги;
 * - пользовательские шрифты.
 *
 * Системный Android TTS и его голоса сюда намеренно не входят: они находятся
 * вне данных приложения и управляются самим Android.
 */
class BackupManager(private val context: Context) {

    data class Progress(val current: Int, val total: Int, val name: String)

    data class Result(
        val files: Int,
        val bytes: Long,
        val cancelled: Boolean = false,
    )

    private val root: File get() = NovaStorage.rootDir(context)
        private val books: File get() = NovaStorage.booksDir(context)
            private val prefsName = "novareader_settings"
            private val bookPrefsName = "novareader_book"

            fun backup(uri: Uri, cancel: AtomicBoolean, onProgress: (Progress) -> Unit): Result {
                val out = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Не удалось открыть файл для записи")
                return out.use { backupToOutput(it, cancel, onProgress) }
            }

            /** Резервная копия в обычный файл выбранный встроенным файловым менеджером. */
            fun backup(file: File, cancel: AtomicBoolean, onProgress: (Progress) -> Unit): Result {
                file.parentFile?.mkdirs()
                return FileOutputStream(file).use { backupToOutput(it, cancel, onProgress) }
            }

            private fun backupToOutput(out: OutputStream, cancel: AtomicBoolean, onProgress: (Progress) -> Unit): Result {
                val entries = collectFiles()
                var written = 0
                var bytes = 0L
                ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                    writeTextEntry(zip, "novareader_backup.marker", markerJson())
                    for ((arcName, file) in entries) {
                        if (cancel.get()) return Result(written, bytes, true)
                            writeFileEntry(zip, arcName, file)
                            written++
                            bytes += file.length()
                            onProgress(Progress(written, entries.size, arcName))
                    }
                    writeTextEntry(zip, "backup_manifest.json", manifestJson(entries.size))
                }
                return Result(written, bytes, false)
            }

            /** Восстанавливает архив прямо в текущую установку NovaReader. */
            fun restore(uri: Uri, cancel: AtomicBoolean, onProgress: (Progress) -> Unit): Result {
                val check = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Не удалось открыть резервную копию")
                val total = check.use { inspectArchive(it) }
                val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Не удалось открыть резервную копию")
                return input.use { restoreFromInput(it, total, cancel, onProgress) }
            }

            /** Восстанавливает ZIP-файл, выбранный встроенным файловым менеджером. */
            fun restore(file: File, cancel: AtomicBoolean, onProgress: (Progress) -> Unit): Result {
                if (!file.isFile) throw IllegalStateException("Файл резервной копии не найден")
                    val total = FileInputStream(file).use { inspectArchive(it) }
                    return FileInputStream(file).use { restoreFromInput(it, total, cancel, onProgress) }
            }

            private fun restoreFromInput(input: InputStream, total: Int, cancel: AtomicBoolean, onProgress: (Progress) -> Unit): Result {
                var current = 0
                var bytes = 0L
                val pendingPrefs = mutableMapOf<String, JSONObject>()

                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    while (true) {
                        if (cancel.get()) return Result(current, bytes, true)
                            val entry = zip.nextEntry ?: break
                            val name = entry.name
                            if (!isSafeEntry(name)) {
                                zip.closeEntry(); current++; onProgress(Progress(current, total, name)); continue
                            }
                            if (entry.isDirectory) {
                                zip.closeEntry(); current++; onProgress(Progress(current, total, name)); continue
                            }
                            when {
                                name == "prefs/settings.json" -> pendingPrefs["settings"] = JSONObject(readUtf8(zip))
                                name == "prefs/book.json" -> pendingPrefs["book"] = JSONObject(readUtf8(zip))
                                name == "novareader_backup.marker" || name == "backup_manifest.json" -> drain(zip)
                                name.startsWith("data/") -> {
                                    val rel = name.removePrefix("data/")
                                    if (rel.isNotBlank()) writeEntryToFile(zip, File(root, rel)) else drain(zip)
                                }
                                name.startsWith("books/") -> {
                                    val rel = name.removePrefix("books/")
                                    if (rel.isNotBlank()) writeEntryToFile(zip, File(books, rel)) else drain(zip)
                                }
                                name.startsWith("fonts/") -> {
                                    val rel = name.removePrefix("fonts/")
                                    if (rel.isNotBlank()) writeEntryToFile(zip, File(root, "fonts/$rel")) else drain(zip)
                                }
                                else -> drain(zip)
                            }
                            if (name.startsWith("data/") || name.startsWith("books/") || name.startsWith("fonts/")) {
                                // Размер фактически записанного файла учитывается в общем счётчике ниже.
                                bytes += entry.compressedSize.coerceAtLeast(0L)
                            }
                            zip.closeEntry(); current++; onProgress(Progress(current, total, name))
                    }
                }
                if (cancel.get()) return Result(current, bytes, true)
                    pendingPrefs["settings"]?.let { restorePrefs(prefsName, it) }
                    pendingPrefs["book"]?.let { restorePrefs(bookPrefsName, it) }
                    return Result(current, bytes, false)
            }

            private fun collectFiles(): List<Pair<String, File>> {
                val out = mutableListOf<Pair<String, File>>()

                // Настройки SharedPreferences — экспортируем типы, чтобы восстановление
                // не превращало Boolean/Int/Float/Long в строки.
                val settingsJson = prefsToJson(prefsName)
                val bookPrefsJson = prefsToJson(bookPrefsName)
                val tempDir = File(context.cacheDir, "backup_prefs").apply { mkdirs() }
                val settingsFile = File(tempDir, "settings.json")
                val bookFile = File(tempDir, "book.json")
                settingsFile.writeText(settingsJson.toString())
                bookFile.writeText(bookPrefsJson.toString())
                out += "prefs/settings.json" to settingsFile
                out += "prefs/book.json" to bookFile

                addTree(out, root.resolve("library.json"), "data/library.json")
                addTree(out, root.resolve("positions.json"), "data/positions.json")
                addTree(out, root.resolve("tts_corrections.json"), "data/tts_corrections.json")
                addTree(out, root.resolve("customTTSColor.json"), "data/customTTSColor.json")
                addTree(out, root.resolve("book_data"), "data/book_data")
                addTree(out, root.resolve("covers"), "data/covers")
                addTree(out, books, "books")
                addTree(out, root.resolve("fonts"), "fonts")
                return out
            }

            private fun addTree(out: MutableList<Pair<String, File>>, source: File, arcBase: String) {
                if (!source.exists()) return
                    if (source.isFile) {
                        out += arcBase to source
                        return
                    }
                    source.walkTopDown().filter { it.isFile }.forEach { file ->
                        val rel = file.relativeTo(source).invariantSeparatorsPath
                        out += "$arcBase/$rel" to file
                    }
            }

            private fun prefsToJson(name: String): JSONObject {
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val root = JSONObject()
                for ((key, value) in prefs.all) {
                    val item = JSONObject()
                    when (value) {
                        is Boolean -> item.put("type", "boolean").put("value", value)
                        is Int -> item.put("type", "int").put("value", value)
                        is Long -> item.put("type", "long").put("value", value)
                        is Float -> item.put("type", "float").put("value", value.toDouble())
                        is String -> item.put("type", "string").put("value", value)
                        is Set<*> -> {
                            val arr = JSONArray()
                            value.filterIsInstance<String>().forEach(arr::put)
                            item.put("type", "string_set").put("value", arr)
                        }
                        null -> item.put("type", "null").put("value", JSONObject.NULL)
                        else -> item.put("type", "string").put("value", value.toString())
                    }
                    root.put(key, item)
                }
                return root
            }

            private fun restorePrefs(name: String, data: JSONObject) {
                val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                val keys = data.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = data.optJSONObject(key) ?: continue
                    when (item.optString("type")) {
                        "boolean" -> editor.putBoolean(key, item.optBoolean("value"))
                        "int" -> editor.putInt(key, item.optInt("value"))
                        "long" -> editor.putLong(key, item.optLong("value"))
                        "float" -> editor.putFloat(key, item.optDouble("value").toFloat())
                        "string" -> editor.putString(key, item.optString("value"))
                        "string_set" -> {
                            val arr = item.optJSONArray("value") ?: JSONArray()
                            val set = mutableSetOf<String>()
                            for (i in 0 until arr.length()) set += arr.optString(i)
                                editor.putStringSet(key, set)
                        }
                    }
                }
                editor.commit()
            }

            private fun markerJson(): String = JSONObject().apply {
                put("format", "NovaReader Android backup")
                put("version", 1)
                put("created", System.currentTimeMillis())
            }.toString()

            private fun manifestJson(fileCount: Int): String = JSONObject().apply {
                put("version", 1)
                put("files", fileCount)
                put("books", "books/")
                put("data", "data/")
                put("fonts", "fonts/")
                put("prefs", "prefs/")
            }.toString()

            private fun inspectArchive(input: InputStream): Int {
                var count = 0
                var markerFound = false
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        count++
                        if (entry.name == "novareader_backup.marker") markerFound = true
                            zip.closeEntry()
                    }
                }
                if (!markerFound) throw IllegalArgumentException("Это не резервная копия NovaReader")
                    return count.coerceAtLeast(1)
            }

            private fun isSafeEntry(name: String): Boolean {
                if (name.isBlank() || name.startsWith('/') || name.contains('\\')) return false
                    val normalized = name.split('/').filter { it.isNotEmpty() }
                    return normalized.none { it == ".." }
            }

            // ── Вспомогательные методы для работы с ZIP ──────────────────────

            /** Записывает текстовую строку в ZIP-архив. */
            private fun writeTextEntry(zip: ZipOutputStream, name: String, content: String) {
                val entry = ZipEntry(name)
                zip.putNextEntry(entry)
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            /** Записывает файл с диска в ZIP-архив. */
            private fun writeFileEntry(zip: ZipOutputStream, name: String, file: File) {
                val entry = ZipEntry(name)
                zip.putNextEntry(entry)
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(8192)
                    var len: Int
                    while (input.read(buffer).also { len = it } != -1) {
                        zip.write(buffer, 0, len)
                    }
                }
                zip.closeEntry()
            }

            /** Читает содержимое текущей записи ZIP как строку UTF-8. */
            private fun readUtf8(zip: ZipInputStream): String {
                return zip.readBytes().toString(Charsets.UTF_8)
            }

            /** Полностью вычитывает и игнорирует текущую запись ZIP. */
            private fun drain(zip: ZipInputStream) {
                val buffer = ByteArray(8192)
                while (zip.read(buffer) != -1) {
                    // просто читаем в никуда
                }
            }

            /** Записывает текущую запись ZIP в указанный файл. */
            private fun writeEntryToFile(zip: ZipInputStream, target: File) {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(8192)
                    var len: Int
                    while (zip.read(buffer).also { len = it } != -1) {
                        out.write(buffer, 0, len)
                    }
                }
            }
}
