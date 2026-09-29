package com.xiaosiqi.quizbank.excel

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 手写的最小 XLSX 写出器（零依赖）。
 * 使用 inlineStr 承载文本，免去 sharedStrings 表，Excel / WPS / 手机端都能正常打开。
 */
object XlsxWriter {

    class Sheet(
        val name: String,
        val rows: List<List<Any?>>,
        val columnWidths: List<Int> = emptyList(),
        val boldHeader: Boolean = true,
    )

    fun write(sheets: List<Sheet>): ByteArray {
        require(sheets.isNotEmpty()) { "至少需要一个工作表" }
        val names = uniqueNames(sheets.map { it.name })
        val out = ByteArrayOutputStream(64 * 1024)
        ZipOutputStream(out).use { zip ->
            zip.put("[Content_Types].xml", contentTypes(sheets.size))
            zip.put("_rels/.rels", rootRels())
            zip.put("xl/workbook.xml", workbook(names))
            zip.put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            zip.put("xl/styles.xml", styles())
            sheets.forEachIndexed { i, sheet ->
                zip.put("xl/worksheets/sheet${i + 1}.xml", worksheet(sheet))
            }
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun uniqueNames(raw: List<String>): List<String> {
        val used = HashSet<String>()
        return raw.mapIndexed { i, name ->
            var base = name.replace(Regex("[\\[\\]:*?/\\\\]"), "").trim().ifEmpty { "Sheet${i + 1}" }
            if (base.length > 31) base = base.substring(0, 31)
            var candidate = base
            var n = 2
            while (!used.add(candidate)) {
                candidate = (base.take(28)) + "_$n"
                n++
            }
            candidate
        }
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> {
                    val code = ch.code
                    // XML 1.0 不允许的控制字符与落单代理项直接丢弃，否则 Excel 会报文件损坏
                    if (code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') continue
                    if (ch.isSurrogate() && !isValidPair(s, ch)) continue
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    private fun isValidPair(s: String, ch: Char): Boolean {
        val i = s.indexOf(ch)
        if (i < 0) return true
        val next = s.getOrNull(i + 1) ?: return false
        return Character.isSurrogatePair(ch, next)
    }

    private fun cellRef(col: Int, row: Int): String {
        var c = col
        val sb = StringBuilder()
        while (true) {
            sb.append(('A' + c % 26))
            c = c / 26 - 1
            if (c < 0) break
        }
        return sb.reverse().toString() + (row + 1)
    }

    private fun worksheet(sheet: Sheet): String {
        val sb = StringBuilder(16 * 1024)
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        if (sheet.columnWidths.isNotEmpty()) {
            sb.append("<cols>")
            sheet.columnWidths.forEachIndexed { i, w ->
                sb.append("""<col min="${i + 1}" max="${i + 1}" width="${w.coerceIn(4, 80)}" customWidth="1"/>""")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        sheet.rows.forEachIndexed { r, row ->
            sb.append("""<row r="${r + 1}">""")
            val isHeader = r == 0 && sheet.boldHeader
            row.forEachIndexed { c, value ->
                if (value == null) return@forEachIndexed
                val text = value.toString()
                if (text.isEmpty()) return@forEachIndexed
                val style = if (isHeader) """ s="1"""" else ""
                val ref = cellRef(c, r)
                if (value is Number && text.toDoubleOrNull() != null) {
                    sb.append("""<c r="$ref"$style><v>$text</v></c>""")
                } else {
                    sb.append("""<c r="$ref"$style t="inlineStr"><is><t xml:space="preserve">""")
                    sb.append(esc(text))
                    sb.append("</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun contentTypes(count: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        sb.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        sb.append("""<Default Extension="xml" ContentType="application/xml"/>""")
        sb.append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        sb.append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..count) {
            sb.append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        sb.append("</Types>")
        return sb.toString()
    }

    private fun rootRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private fun workbook(names: List<String>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        sb.append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        names.forEachIndexed { i, name ->
            sb.append("""<sheet name="${esc(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        sb.append("</sheets></workbook>")
        return sb.toString()
    }

    private fun workbookRels(count: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..count) {
            sb.append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        sb.append("""<Relationship Id="rId${count + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun styles(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="2">""" +
            """<font><sz val="11"/><color theme="1"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>""" +
            """</fonts>""" +
            """<fills count="3">""" +
            """<fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FF2563EB"/><bgColor indexed="64"/></patternFill></fill>""" +
            """</fills>""" +
            """<borders count="1"><border/></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="2">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"><alignment vertical="center"/></xf>""" +
            """</cellXfs>""" +
            """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
            """</styleSheet>"""
}
