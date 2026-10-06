package com.novareader.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

internal object MobiParser {

    private const val PDB_HEADER_SIZE = 78
    private const val PDB_RECORD_ENTRY_SIZE = 8

    private data class Field(val start: Int, val length: Int, val type: FieldType)
    private enum class FieldType { UINT, STRING }

    private val PDB_HEADER = mapOf(
        "name" to Field(0, 32, FieldType.STRING),
        "type" to Field(60, 4, FieldType.STRING),
        "creator" to Field(64, 4, FieldType.STRING),
        "numRecords" to Field(76, 2, FieldType.UINT),
    )

    private val MOBI_HEADER = mapOf(
        "magic" to Field(16, 4, FieldType.STRING),
        "length" to Field(20, 4, FieldType.UINT),
        "encoding" to Field(28, 4, FieldType.UINT),
        "version" to Field(36, 4, FieldType.UINT),
        "titleOffset" to Field(84, 4, FieldType.UINT),
        "titleLength" to Field(88, 4, FieldType.UINT),
        "resourceStart" to Field(108, 4, FieldType.UINT),
        "exthFlag" to Field(128, 4, FieldType.UINT),
    )

    private val EXTH_HEADER = mapOf(
        "magic" to Field(0, 4, FieldType.STRING),
        "length" to Field(4, 4, FieldType.UINT),
        "count" to Field(8, 4, FieldType.UINT),
    )

    private data class ExthType(val name: String, val many: Boolean = false)
    private val EXTH_RECORD_TYPE: Map<Int, ExthType> = mapOf(
        100 to ExthType("creator", many = true),
        101 to ExthType("publisher"),
        103 to ExthType("description"),
        106 to ExthType("date"),
        108 to ExthType("contributor", many = true),
        109 to ExthType("rights"),
        121 to ExthType("boundary"),
        129 to ExthType("coverURI"),
        201 to ExthType("coverOffset"),
        202 to ExthType("thumbnailOffset"),
        503 to ExthType("title"),
        524 to ExthType("language", many = true),
        527 to ExthType("pageProgressionDirection"),
    )

    data class MobiMetadata(
        val title: String?,
        val authors: List<String>,
        val language: String?,
    )

    /**
     * Извлекает title/author из MOBI/AZW3. Для метаданных полезно
     * переключиться на KF8-заголовок (там обычно лежит «настоящий»
     * title, а в MOBI6-EXTH — служебное "EBOK").
     *
     * Обложка этим методом НЕ извлекается — для неё отдельный
     * extractCoverBytes, который работает строго по первому
     * MOBI-заголовку (см. пояснение ниже).
     */
    fun extract(file: File, fallbackTitle: String): MobiMetadata {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < (PDB_HEADER_SIZE + PDB_RECORD_ENTRY_SIZE).toLong()) {
                return fallbackOf(fallbackTitle)
            }
            raf.seek(0L)
            val pdbBytes = raf.readBytes(PDB_HEADER_SIZE.toLong())
            val pdb = parseStruct(PDB_HEADER, pdbBytes)
            val numRecords: Int = (pdb["numRecords"] as? Long)?.toInt() ?: 0
            if (numRecords <= 0 || numRecords > 100_000) return fallbackOf(fallbackTitle)

            val offsets = LongArray(numRecords)
            raf.seek(PDB_HEADER_SIZE.toLong())
            val offsetBytes = raf.readBytes((numRecords * PDB_RECORD_ENTRY_SIZE).toLong())
            var i = 0
            while (i < numRecords) {
                offsets[i] = readU32BE(offsetBytes, i * PDB_RECORD_ENTRY_SIZE)
                i++
            }

            val firstRecord = readRecord(raf, offsets, 0) ?: return fallbackOf(fallbackTitle)
            val mobi6Headers = parseHeaders(firstRecord) ?: return fallbackOf(fallbackTitle)

            var currentHeaders = mobi6Headers
            var currentRecord = firstRecord
            var isKf8 = false

            val boundary = mobi6Headers.boundary
            if (boundary >= 0L && boundary < numRecords.toLong()) {
                val kf8Raw = readRecord(raf, offsets, boundary.toInt())
                if (kf8Raw != null) {
                    val kf8Headers = parseHeaders(kf8Raw)
                    if (kf8Headers != null && looksLikeRealKf8(kf8Headers)) {
                        currentHeaders = kf8Headers
                        currentRecord = kf8Raw
                        isKf8 = true
                    }
                }
            }

            val encoding = currentHeaders.encoding

            val exthTitle = currentHeaders.exthTitle?.trim()
                ?.takeIf { it.isNotEmpty() && !looksLikeGarbage(it) }
            val mobiTitle = decodeString(
                currentRecord.sliceOrEmpty(
                    currentHeaders.titleOffset.toInt(),
                    currentHeaders.titleLength.toInt()
                ),
                encoding
            ).trim().takeIf { it.isNotEmpty() && !looksLikeGarbage(it) }
            val title = (exthTitle ?: mobiTitle)?.takeIf { it.isNotEmpty() }

            val authors = currentHeaders.authors
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { !it.equals("Unknown", ignoreCase = true) }
                .filter { !it.equals("EBOK", ignoreCase = true) }
                .filter { !it.equals("anonymous", ignoreCase = true) }
                .distinct()

            val language = currentHeaders.languages.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

            return MobiMetadata(
                title = title,
                authors = authors,
                language = language,
            )
        }
    }

    /**
     * Извлекает байты обложки тем же способом, что был в самой первой
     * версии LibraryManager до интеграции MobiParser. Именно этот способ
     * давал правильную обложку для книг Эксмо/АСТ.
     *
     * Ключевой момент: и `resourceStart`, и `coverOffset`/`thumbnailOffset`
     * берутся из ПЕРВОГО MOBI-заголовка, и EXTH читается СРАЗУ за ним же.
     * Никакого переключения на KF8 — обложка в этих книгах лежит именно
     * в ресурсах MOBI6, и её нельзя найти в KF8-ресурсах.
     *
     * Возвращает байты картинки или null.
     */
    fun extractCoverBytes(file: File): ByteArray? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < PDB_HEADER_SIZE.toLong()) return null

            raf.seek(0L)
            val pdbBytes = raf.readBytes(PDB_HEADER_SIZE.toLong())
            val pdb = parseStruct(PDB_HEADER, pdbBytes)
            val numRecords: Int = (pdb["numRecords"] as? Long)?.toInt() ?: 0
            if (numRecords <= 1 || numRecords > 100_000) return null

            val offsets = LongArray(numRecords)
            raf.seek(PDB_HEADER_SIZE.toLong())
            val offsetBytes = raf.readBytes((numRecords * PDB_RECORD_ENTRY_SIZE).toLong())
            var i = 0
            while (i < numRecords) {
                offsets[i] = readU32BE(offsetBytes, i * PDB_RECORD_ENTRY_SIZE)
                i++
            }

            // Работаем строго с первой записью (MOBI6-заголовок).
            val first = offsets[0]
            val second = offsets[1]
            if (first < 0L || second <= first || second > raf.length()) return null

            // Читаем MOBI-заголовок первой записи.
            raf.seek(first + 16L)
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "MOBI") return null

            // mobiLength по offset +20 от начала MOBI-заголовка (т.е. first+20)
            raf.seek(first + 20L)
            val mobiLength = readU32BE(raf)

            // resourceStart по offset +108 от начала MOBI-заголовка
            raf.seek(first + 108L)
            val resourceStart = readU32BE(raf)

            // exthFlag по offset +128 от начала MOBI-заголовка
            raf.seek(first + 128L)
            val exthFlag = readU32BE(raf)
            if ((exthFlag and 0x40L) == 0L) return null

            // EXTH начинается сразу после MOBI-заголовка: first + 16 + mobiLength.
            // ВАЖНО: тот же first, из которого взяли resourceStart — это и
            // обеспечивает согласованность индексов.
            val exthStart = first + 16L + mobiLength
            if (exthStart + 12L > second) return null

            raf.seek(exthStart)
            val exthMagic = ByteArray(4)
            raf.readFully(exthMagic)
            if (String(exthMagic, Charsets.US_ASCII) != "EXTH") return null

            val exthLength = readU32BE(raf)
            val exthCount = readU32BE(raf).coerceIn(0L, 1000L)
            val exthEnd = (exthStart + exthLength).coerceAtMost(second)

            var pos = exthStart + 12L
            var coverOffset: Long? = null
            var thumbnailOffset: Long? = null
            var idx = 0L
            while (idx < exthCount && pos + 8L <= exthEnd) {
                raf.seek(pos)
                val type = readU32BE(raf)
                val len = readU32BE(raf)
                if (len < 8L || pos + len > exthEnd) break
                if (type == 201L || type == 202L) {
                    raf.seek(pos + 8L)
                    val value = readU32BE(raf)
                    if (type == 201L) coverOffset = value else thumbnailOffset = value
                }
                pos += len
                idx++
            }

            val resourceIndex = coverOffset ?: thumbnailOffset ?: return null
            val resourceRecord = (resourceStart + resourceIndex).toInt()
            if (resourceRecord < 0 || resourceRecord >= numRecords) return null

            val start = offsets[resourceRecord]
            val end = if (resourceRecord + 1 < numRecords) offsets[resourceRecord + 1] else raf.length()
            if (start < 0L || end <= start || end > raf.length()) return null

            raf.seek(start)
            return raf.readBytes((end - start).coerceAtMost(64L * 1024L * 1024L))
        }
    }

    // ── вспомогательные ───────────────────────────────────────────────

    private fun fallbackOf(fallbackTitle: String) = MobiMetadata(
        title = fallbackTitle.substringBeforeLast('.').takeIf { it.isNotEmpty() },
        authors = emptyList(),
        language = null,
    )

    private fun looksLikeRealKf8(h: Headers): Boolean =
        (h.exthTitle?.isNotBlank() == true) ||
                h.authors.isNotEmpty() ||
                (h.titleLength > 0L && h.titleOffset > 0L)

    private fun looksLikeGarbage(s: String): Boolean {
        val t = s.trim()
        if (t.length < 4) return true
        if (t.equals("EBOK", ignoreCase = true)) return true
        if (t.equals("Unknown", ignoreCase = true)) return true
        if (t.equals("anonymous", ignoreCase = true)) return true
        if (t.count { it.isLetter() } < 3) return true
        return false
    }

    private class Headers(
        val version: Long,
        val encoding: Long,
        val titleOffset: Long,
        val titleLength: Long,
        val resourceStart: Long,
        val boundary: Long,
        val exthTitle: String?,
        val authors: List<String>,
        val languages: List<String>,
        val coverOffset: Long?,
        val thumbnailOffset: Long?,
    )

    private fun parseHeaders(buf: ByteArray): Headers? {
        val mobiMap = parseStruct(MOBI_HEADER, buf)
        val magic = mobiMap["magic"] as? String ?: return null
        if (magic != "MOBI") return null

        val version = (mobiMap["version"] as? Long) ?: 0L
        val encoding = (mobiMap["encoding"] as? Long) ?: 65001L
        val titleOffset = (mobiMap["titleOffset"] as? Long) ?: 0L
        val titleLength = (mobiMap["titleLength"] as? Long) ?: 0L
        val resourceStart = (mobiMap["resourceStart"] as? Long) ?: 0L
        val exthFlag = (mobiMap["exthFlag"] as? Long) ?: 0L
        val mobiLength = (mobiMap["length"] as? Long) ?: 0L

        var exthTitle: String? = null
        var authors: List<String> = emptyList()
        var languages: List<String> = emptyList()
        var coverOffset: Long? = null
        var thumbnailOffset: Long? = null
        var boundary: Long = -1L

        if ((exthFlag and 0x40L) != 0L) {
            val exthStartLong = mobiLength + 16L
            if (exthStartLong >= 0L && exthStartLong < buf.size.toLong()) {
                val exth = parseExth(buf, exthStartLong.toInt(), encoding)
                if (exth != null) {
                    exthTitle = exth["title"] as? String
                    @Suppress("UNCHECKED_CAST")
                    authors = (exth["creator"] as? List<String>) ?: emptyList()
                    @Suppress("UNCHECKED_CAST")
                    languages = (exth["language"] as? List<String>) ?: emptyList()
                    coverOffset = exth["coverOffset"] as? Long
                    thumbnailOffset = exth["thumbnailOffset"] as? Long
                    boundary = (exth["boundary"] as? Long) ?: -1L
                }
            }
        }

        return Headers(
            version = version,
            encoding = encoding,
            titleOffset = titleOffset,
            titleLength = titleLength,
            resourceStart = resourceStart,
            boundary = boundary,
            exthTitle = exthTitle,
            authors = authors,
            languages = languages,
            coverOffset = coverOffset,
            thumbnailOffset = thumbnailOffset,
        )
    }

    private fun parseExth(buf: ByteArray, start: Int, encoding: Long): Map<String, Any>? {
        if (start.toLong() + 12L > buf.size.toLong()) return null
        val header = buf.sliceArray(start until buf.size)
        val exthHeader = parseStruct(EXTH_HEADER, header)
        if (exthHeader["magic"] != "EXTH") return null

        val lengthLong = (exthHeader["length"] as? Long) ?: return null
        val countLong = (exthHeader["count"] as? Long) ?: 0L
        if (lengthLong < 12L) return null
        if (start.toLong() + lengthLong > buf.size.toLong()) return null
        if (countLong <= 0L || countLong > 10_000L) return null

        val length = lengthLong.toInt()
        val count = countLong.toInt()
        val charset = charsetFor(encoding)

        val result = mutableMapOf<String, Any>()
        var offset = start + 12
        val end = start + length
        var i = 0
        while (i < count && offset + 8 <= end) {
            val type = readU32BE(buf, offset).toInt()
            val len = readU32BE(buf, offset + 4).toInt()
            if (len < 8 || offset + len > end) break

            val def = EXTH_RECORD_TYPE[type]
            if (def != null) {
                val data = buf.sliceArray(offset + 8 until offset + len)
                val isNumeric = type == 201 || type == 202 || type == 121 || type == 129
                val value: Any = if (isNumeric) readU32BE(buf, offset + 8)
                else String(data, charset).replace("\u0000", "")
                if (def.many) {
                    @Suppress("UNCHECKED_CAST")
                    val existing = result[def.name] as? MutableList<Any>
                    if (existing != null) existing.add(value)
                    else result[def.name] = mutableListOf(value)
                } else {
                    result[def.name] = value
                }
            }
            offset += len
            i++
        }
        return result
    }

    private fun readRecord(raf: RandomAccessFile, offsets: LongArray, index: Int): ByteArray? {
        if (index < 0 || index >= offsets.size) return null
        val start = offsets[index]
        val end = if (index + 1 < offsets.size) offsets[index + 1] else raf.length()
        if (start < 0L || end <= start || end > raf.length()) return null
        val count = (end - start).coerceAtMost(64L * 1024L * 1024L)
        if (count <= 0L) return null
        raf.seek(start)
        return raf.readBytes(count)
    }

    private fun parseStruct(def: Map<String, Field>, buf: ByteArray): Map<String, Any> {
        val result = HashMap<String, Any>(def.size)
        for ((key, field) in def) {
            if (field.start + field.length > buf.size) continue
            result[key] = when (field.type) {
                FieldType.STRING ->
                    String(buf, field.start, field.length, Charsets.US_ASCII)
                FieldType.UINT -> when (field.length) {
                    1 -> (buf[field.start].toInt() and 0xFF).toLong()
                    2 -> readU16BE(buf, field.start).toLong()
                    4 -> readU32BE(buf, field.start)
                    else -> 0L
                }
            }
        }
        return result
    }

    private fun readU16BE(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun readU32BE(buf: ByteArray, offset: Int): Long =
        ((buf[offset].toLong() and 0xFFL) shl 24) or
                ((buf[offset + 1].toLong() and 0xFFL) shl 16) or
                ((buf[offset + 2].toLong() and 0xFFL) shl 8) or
                (buf[offset + 3].toLong() and 0xFFL)

    private fun readU32BE(raf: RandomAccessFile): Long =
        ((raf.readUnsignedByte().toLong() shl 24) or
                (raf.readUnsignedByte().toLong() shl 16) or
                (raf.readUnsignedByte().toLong() shl 8) or
                (raf.readUnsignedByte().toLong())) and 0xFFFFFFFFL

    private fun ByteArray.sliceOrEmpty(start: Int, length: Int): ByteArray {
        if (length <= 0 || start < 0 || start.toLong() + length.toLong() > this.size.toLong()) {
            return ByteArray(0)
        }
        return this.copyOfRange(start, start + length)
    }

    private fun RandomAccessFile.readBytes(count: Long): ByteArray {
        if (count <= 0L) return ByteArray(0)
        val n = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes = ByteArray(n)
        this.readFully(bytes)
        return bytes
    }

    private fun charsetFor(encoding: Long): Charset =
        if (encoding == 65001L) Charsets.UTF_8 else Charset.forName("windows-1252")

    private fun decodeString(bytes: ByteArray, encoding: Long): String =
        String(bytes, charsetFor(encoding))
}