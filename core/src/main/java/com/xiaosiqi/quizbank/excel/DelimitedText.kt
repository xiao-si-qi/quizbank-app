package com.xiaosiqi.quizbank.excel

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * CSV / TSV 读取。相比 XLSX 最关键的一点是「编码」：
 * 国内用户用 Excel 另存的 CSV 大多是 GBK 而不是 UTF-8，
 * 直接按 UTF-8 读会整片乱码——这正是「对普通用户不友好」的常见来源，所以这里做自动识别。
 */
object DelimitedText {

    private val UTF8 = Charsets.UTF_8
    private val GB18030: Charset = runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.ISO_8859_1)
    private val UTF16LE: Charset = Charsets.UTF_16LE
    private val UTF16BE: Charset = Charsets.UTF_16BE

    data class Decoded(val text: String, val charsetName: String)

    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Decoded(String(bytes, 3, bytes.size - 3, UTF8), "UTF-8(BOM)")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, UTF16LE), "UTF-16LE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, UTF16BE), "UTF-16BE")
        }
        strictDecode(bytes, UTF8)?.let { return Decoded(it, "UTF-8") }
        strictDecode(bytes, GB18030)?.let { return Decoded(it, "GB18030/GBK") }
        return Decoded(String(bytes, Charsets.ISO_8859_1), "ISO-8859-1")
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = runCatching {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    /** 猜测分隔符：逗号 / 制表符 / 分号 / 竖线，取表头行中出现次数最多者。 */
    fun detectDelimiter(text: String): Char {
        val head = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        val candidates = listOf(',', '\t', ';', '|')
        var best = ','
        var bestCount = 0
        for (c in candidates) {
            val count = countOutsideQuotes(head, c)
            if (count > bestCount) { bestCount = count; best = c }
        }
        return if (bestCount == 0) ',' else best
    }

    private fun countOutsideQuotes(line: String, delimiter: Char): Int {
        var inQuotes = false
        var count = 0
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> count++
            }
        }
        return count
    }

    /** RFC4180 解析，支持字段内换行、双引号转义（""）。 */
    fun parse(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        var fieldStarted = false
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i += 2; continue }
                    inQuotes = false; i++; continue
                }
                field.append(ch); i++; continue
            }
            when (ch) {
                '"' -> { inQuotes = true; fieldStarted = true; i++ }
                delimiter -> { row.add(field.toString().trim()); field.setLength(0); fieldStarted = false; i++ }
                '\r' -> {
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(field.toString().trim()); field.setLength(0); fieldStarted = false
                    rows.add(row); row = ArrayList(); i++
                }
                '\n' -> {
                    row.add(field.toString().trim()); field.setLength(0); fieldStarted = false
                    rows.add(row); row = ArrayList(); i++
                }
                else -> { field.append(ch); fieldStarted = true; i++ }
            }
        }
        if (field.isNotEmpty() || fieldStarted || row.isNotEmpty()) {
            row.add(field.toString().trim())
            rows.add(row)
        }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }
}
