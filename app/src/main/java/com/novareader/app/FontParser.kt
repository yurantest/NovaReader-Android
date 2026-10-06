package com.novareader.app

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Минимальный разбор sfnt-контейнера (TTF/OTF) — извлекает ровно то, что
 * нужно reader.html для @font-face (см. getThemeCSS() в reader.html):
 * family name (таблица `name`), вес (таблица `OS/2`.usWeightClass),
 * курсив (OS/2.fsSelection бит 0, либо head.macStyle бит 1), и диапазон
 * веса для вариативных шрифтов (таблица `fvar`, ось 'wght').
 *
 * На десктопе это делал Qt (QFontDatabase.addApplicationFont + разбор через
 * Qt's font engine) — на Android такого готового API нет, поэтому парсим
 * сами. Не претендует на полноту спецификации OpenType — ровно тот минимум
 * полей, которые reader.html реально использует.
 *
 * uniqueFamily — уникальное внутреннее имя для JS FontFace API. Нужно,
 * чтобы два начертания одного семейства (например, KF Bitter Regular
 * и KF Bitter Bold) регистрировались в document.fonts под РАЗНЫМИ
 * именами и не смешивались: браузер иначе возьмёт первое загруженное
 * начертание и подставит его глифы для всех weight. В reader.html и
 * fonts.js это имя используется как `__NovaReader_<uniqueFamily>`.
 */
data class ParsedFontInfo(
    val family: String,
    val weight: Int,
    val italic: Boolean,
    val variable: Boolean,
    val wghtMin: Int,
    val wghtMax: Int,
    val uniqueFamily: String,
)

object FontParser {

    fun parse(file: java.io.File): ParsedFontInfo? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(12)
                raf.readFully(header)
                val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                val sfntVersion = buf.int
                // 'true'/0x00010000 = TTF, 'OTTO' = OTF — оба валидны.
                if (sfntVersion != 0x00010000 && sfntVersion != 0x4F54544F) return null
                    val numTables = buf.short.toInt() and 0xFFFF

                    val tables = mutableMapOf<String, Pair<Int, Int>>() // tag -> (offset, length)
                    val tableRecord = ByteArray(16)
                    for (i in 0 until numTables) {
                        raf.readFully(tableRecord)
                        val tb = ByteBuffer.wrap(tableRecord).order(ByteOrder.BIG_ENDIAN)
                        val tag = String(tableRecord, 0, 4, Charsets.US_ASCII)
                        tb.position(8)
                        val offset = tb.int
                        val length = tb.int
                        tables[tag] = offset to length
                    }

                    val family = tables["name"]?.let { (offset, length) -> readFamilyName(raf, offset, length) }
                    ?: file.nameWithoutExtension

                    var weight = 400
                    var italic = false
                    var italicFromFsSelection = false
                    tables["OS/2"]?.let { (offset, _) ->
                        raf.seek(offset.toLong())
                        val os2 = ByteArray(96)
                        val read = raf.read(os2)
                        if (read >= 62) {
                            val ob = ByteBuffer.wrap(os2).order(ByteOrder.BIG_ENDIAN)
                            weight = ob.getShort(4).toInt() and 0xFFFF
                            val fsSelection = ob.getShort(62).toInt() and 0xFFFF
                            italic = (fsSelection and 0x01) != 0
                            italicFromFsSelection = italic
                        }
                    }

                    // Fallback для italic из head.macStyle (бит 1) — на случай,
                    // если OS/2 отсутствует или fsSelection не выставлен, но
                    // в head.macStyle курсив помечен (редко, но бывает).
                    if (!italic) {
                        tables["head"]?.let { (offset, _) ->
                            raf.seek(offset.toLong() + 44)
                            val macStyleBytes = ByteArray(2)
                            val read = raf.read(macStyleBytes)
                            if (read == 2) {
                                val macStyle = ByteBuffer.wrap(macStyleBytes).order(ByteOrder.BIG_ENDIAN)
                                .short.toInt() and 0xFFFF
                                if ((macStyle and 0x02) != 0) italic = true
                            }
                        }
                    }

                    var variable = false
                    var wghtMin = weight
                    var wghtMax = weight
                    tables["fvar"]?.let { (offset, _) ->
                        raf.seek(offset.toLong())
                        val fvarHeader = ByteArray(16)
                        raf.readFully(fvarHeader)
                        val fb = ByteBuffer.wrap(fvarHeader).order(ByteOrder.BIG_ENDIAN)
                        val axesArrayOffset = fb.getShort(4).toInt() and 0xFFFF
                        val axisCount = fb.getShort(8).toInt() and 0xFFFF
                        val axisSize = fb.getShort(10).toInt() and 0xFFFF
                        raf.seek(offset.toLong() + axesArrayOffset)
                        for (a in 0 until axisCount) {
                            val axis = ByteArray(axisSize)
                            raf.readFully(axis)
                            val tag = String(axis, 0, 4, Charsets.US_ASCII)
                            if (tag == "wght") {
                                val ab = ByteBuffer.wrap(axis).order(ByteOrder.BIG_ENDIAN)
                                wghtMin = (ab.getInt(4) shr 16)
                                wghtMax = (ab.getInt(12) shr 16)
                                variable = true
                            }
                        }
                    }

                    // ── Fallback по имени файла ─────────────────────────────────
                    // Некоторые авторы шрифтов не заполняют nameID=17 (Preferred
                    // Subfamily) корректно: например, в Bold-файле пишут
                    // subfamily='Regular'. В этом случае weight остаётся 400,
                    // и Bold-файл регистрируется как Regular. Читаем начертание
                    // из имени файла как последний приоритет.
                    //
                    // ВАЖНО: на Android мы не разбираем nameID=17 (в отличие от
                    // Python-парсера на ПК), поэтому здесь просто вытаскиваем
                    // вес/стиль из имени файла, если OS/2 не дал ничего явного.
                    val fnameLower = file.nameWithoutExtension.lowercase()
                    if (weight == 400) {
                        val weightKeywords = listOf(
                            "extrabold" to 800, "ultrabold" to 800,
                            "semibold" to 600, "demibold" to 600,
                            "bold" to 700,
                            "black" to 900, "heavy" to 900,
                        )
                        for ((kw, w) in weightKeywords) {
                            if (fnameLower.contains(kw)) {
                                weight = w
                                break
                            }
                        }
                    }
                    if (!italic && (fnameLower.contains("italic") || fnameLower.contains("oblique"))) {
                        italic = true
                    }

                    // ── Уникальное внутреннее имя для JS FontFace API ───────────
                    // family + суффикс веса/стиля:
                    //   'KF Bitter' Regular → 'KF Bitter'
                    //   'KF Bitter' Bold    → 'KF Bitter 700'
                    //   'KF Bitter' Italic  → 'KF Bitter Italic'
                    //   'KF Bitter' BoldIt. → 'KF Bitter 700 Italic'
                    //
                    // Используется только в JS как '__NovaReader_<uniqueFamily>'.
                    // Python-сторона на ПК вычисляет то же самое.
                    val styleForSuffix = if (italic) "Italic" else null
                    val uniqueFamily = buildString {
                        append(family)
                        if (weight != 400) {
                            append(' ')
                            append(weight)
                        }
                        if (styleForSuffix != null) {
                            append(' ')
                            append(styleForSuffix)
                        }
                    }

                    ParsedFontInfo(family, weight, italic, variable, wghtMin, wghtMax, uniqueFamily)
            }
        } catch (e: Exception) {
            NovaLog.e("NovaReader.FontParser", "Не удалось разобрать шрифт ${file.name}: ${e.message}")
            null
        }
    }

    private fun readFamilyName(raf: RandomAccessFile, tableOffset: Int, @Suppress("UNUSED_PARAMETER") tableLength: Int): String? {
        raf.seek(tableOffset.toLong())
        val header = ByteArray(6)
        raf.readFully(header)
        val hb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val count = hb.getShort(2).toInt() and 0xFFFF
        val stringOffset = hb.getShort(4).toInt() and 0xFFFF

        data class Candidate(val nameId: Int, val platformId: Int, val offset: Int, val length: Int, val encoding: Int)
        val candidates = mutableListOf<Candidate>()

        val record = ByteArray(12)
        for (i in 0 until count) {
            raf.readFully(record)
            val rb = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN)
            val platformId = rb.getShort(0).toInt() and 0xFFFF
            val encodingId = rb.getShort(2).toInt() and 0xFFFF
            val nameId = rb.getShort(6).toInt() and 0xFFFF
            val length = rb.getShort(8).toInt() and 0xFFFF
            val offset = rb.getShort(10).toInt() and 0xFFFF
            if (nameId == 16 || nameId == 1) {
                candidates.add(Candidate(nameId, platformId, offset, length, encodingId))
            }
        }
        val best = candidates.sortedWith(
            compareByDescending<Candidate> { it.nameId == 16 }.thenByDescending { it.platformId == 3 || it.platformId == 0 }
        ).firstOrNull() ?: return null

        raf.seek(tableOffset.toLong() + stringOffset + best.offset)
        val bytes = ByteArray(best.length)
        raf.readFully(bytes)
        return try {
            if (best.platformId == 1) {
                String(bytes, Charsets.ISO_8859_1).trim()
            } else {
                String(bytes, Charsets.UTF_16BE).trim()
            }
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotBlank() }
    }
}
