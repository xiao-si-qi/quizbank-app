package com.xiaosiqi.quizbank.excel

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * 手写的 XLSX 读取器（零第三方依赖）。
 *
 * 为什么不用 Apache POI：POI 依赖 java.awt 与 javax.xml.stream，
 * 这两者在 Android 上都不存在，运行时必然崩溃，而且体积以十 MB 计。
 * XLSX 本质是 zip + XML，Android 自带的 java.util.zip / javax.xml.parsers 完全够用。
 */
object XlsxReader {

    private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"

    /** 内置日期格式编号。 */
    private val BUILTIN_DATE_FMTS: Set<Int> =
        (14..22).toSet() + (27..36).toSet() + setOf(45, 46, 47, 50, 51, 52, 53, 54, 55, 56, 57, 58)

    fun read(bytes: ByteArray): List<SheetTable> {
        val entries = ZipPicker.pick(bytes) { name ->
            name == "xl/workbook.xml" ||
                name == "xl/_rels/workbook.xml.rels" ||
                name == "xl/sharedStrings.xml" ||
                name == "xl/styles.xml" ||
                (name.startsWith("xl/worksheets/") && name.endsWith(".xml"))
        }
        if (entries.isEmpty()) throw ExcelFormatException("这不是一个有效的 xlsx 文件（未找到 xl/workbook.xml）")

        val shared = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val dateStyles = entries["xl/styles.xml"]?.let { parseDateStyles(it) } ?: BooleanArray(0)

        val sheetRefs = entries["xl/workbook.xml"]?.let { parseWorkbook(it) } ?: emptyList()
        val rels = entries["xl/_rels/workbook.xml.rels"]?.let { parseRels(it) } ?: emptyMap()

        // 计算每个 sheet 对应的 zip 条目路径
        val resolved = ArrayList<Pair<String, String>>() // name -> path
        for (ref in sheetRefs) {
            val target = rels[ref.rid]
            val path = normalizeTarget(target) ?: continue
            if (entries.containsKey(path)) resolved.add(ref.name to path)
        }
        if (resolved.isEmpty()) {
            // 兜底：直接按文件名自然序排列
            entries.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
                .sortedWith(compareBy({ it.length }, { it }))
                .forEachIndexed { i, p -> resolved.add("Sheet${i + 1}" to p) }
        }

        val result = ArrayList<SheetTable>(resolved.size)
        for ((name, path) in resolved) {
            val data = entries[path] ?: continue
            val grid = parseSheet(data, shared, dateStyles)
            result.add(SheetTable(name, grid))
        }
        if (result.isEmpty()) throw ExcelFormatException("xlsx 中没有找到任何工作表")
        return result
    }

    private fun normalizeTarget(target: String?): String? {
        if (target.isNullOrBlank()) return null
        var t = target.replace('\\', '/')
        if (t.startsWith("/")) t = t.removePrefix("/") else t = "xl/$t"
        return t
    }

    // ---------------------------------------------------------------- workbook

    private class SheetRef(val name: String, val rid: String)

    private fun parseWorkbook(bytes: ByteArray): List<SheetRef> {
        val handler = object : DefaultHandler() {
            val out = ArrayList<SheetRef>()
            override fun startElement(uri: String?, local: String?, qName: String?, a: Attributes?) {
                val name = (qName ?: local ?: "").substringAfterLast(':')
                if (name == "sheet") {
                    val sheetName = XmlSax.attr(a, "name") ?: ""
                    val rid = XmlSax.attr(a, "r:id") ?: XmlSax.attr(a, "id") ?: ""
                    out.add(SheetRef(sheetName, rid))
                }
            }
        }
        return runCatching { XmlSax.parse(bytes, handler); handler.out }.getOrDefault(emptyList())
    }

    private fun parseRels(bytes: ByteArray): Map<String, String> {
        val handler = object : DefaultHandler() {
            val out = HashMap<String, String>()
            override fun startElement(uri: String?, local: String?, qName: String?, a: Attributes?) {
                val name = (qName ?: local ?: "").substringAfterLast(':')
                if (name == "Relationship") {
                    val id = XmlSax.attr(a, "Id") ?: return
                    val target = XmlSax.attr(a, "Target") ?: return
                    val type = XmlSax.attr(a, "Type") ?: ""
                    if (type.isEmpty() || type.endsWith("/worksheet")) out[id] = target
                }
            }
        }
        return runCatching { XmlSax.parse(bytes, handler); handler.out }.getOrDefault(emptyMap())
    }

    // ---------------------------------------------------------- sharedStrings

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val handler = object : DefaultHandler() {
            val out = ArrayList<String>()
            private val sb = StringBuilder()
            private val stack = ArrayList<String>(8)
            private var inSi = false
            private var skip = 0

            override fun startElement(uri: String?, local: String?, qName: String?, a: Attributes?) {
                val name = (qName ?: local ?: "").substringAfterLast(':')
                stack.add(name)
                when (name) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    "rPh", "phoneticPr" -> skip++
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inSi && skip == 0 && stack.lastOrNull() == "t") sb.append(ch, start, length)
            }

            override fun endElement(uri: String?, local: String?, qName: String?) {
                val name = (qName ?: local ?: "").substringAfterLast(':')
                when (name) {
                    "si" -> { out.add(sb.toString()); inSi = false; sb.setLength(0) }
                    "rPh", "phoneticPr" -> if (skip > 0) skip--
                }
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
        }
        return runCatching { XmlSax.parse(bytes, handler); handler.out }.getOrDefault(emptyList())
    }

    // ----------------------------------------------------------------- styles

    /** 返回「样式下标 -> 是否日期格式」的布尔数组。 */
    private fun parseDateStyles(bytes: ByteArray): BooleanArray {
        val customDate = HashSet<Int>()
        val xfFmtIds = ArrayList<Int>()
        val handler = object : DefaultHandler() {
            private var inCellXfs = false
            private var inStyleXfs = false
            override fun startElement(uri: String?, local: String?, qName: String?, a: Attributes?) {
                when ((qName ?: local ?: "").substringAfterLast(':')) {
                    "numFmt" -> {
                        val id = XmlSax.attr(a, "numFmtId")?.toIntOrNull() ?: return
                        val code = XmlSax.attr(a, "formatCode") ?: return
                        if (looksLikeDateFormat(code)) customDate.add(id)
                    }
                    "cellXfs" -> inCellXfs = true
                    "cellStyleXfs" -> inStyleXfs = true
                    "xf" -> if (inCellXfs && !inStyleXfs) {
                        xfFmtIds.add(XmlSax.attr(a, "numFmtId")?.toIntOrNull() ?: 0)
                    }
                }
            }

            override fun endElement(uri: String?, local: String?, qName: String?) {
                when ((qName ?: local ?: "").substringAfterLast(':')) {
                    "cellXfs" -> inCellXfs = false
                    "cellStyleXfs" -> inStyleXfs = false
                }
            }
        }
        runCatching { XmlSax.parse(bytes, handler) }
        return BooleanArray(xfFmtIds.size) { i ->
            val id = xfFmtIds[i]
            id in BUILTIN_DATE_FMTS || customDate.contains(id)
        }
    }

    private fun looksLikeDateFormat(code: String): Boolean {
        var s = code
        // 去掉引号内容与方括号（颜色/条件）后再判断
        s = s.replace(Regex("\"[^\"]*\""), "")
        s = s.replace(Regex("\\[[^\\]]*\\]"), "")
        if (s.isEmpty()) return false
        return s.any { it == 'y' || it == 'Y' || it == 'd' || it == 'D' } ||
            (s.contains('m') && (s.contains('/') || s.contains('-') || s.contains('年')))
    }

    // ------------------------------------------------------------------ sheet

    private fun parseSheet(bytes: ByteArray, shared: List<String>, dateStyles: BooleanArray): List<List<String>> {
        val rows = ArrayList<MutableList<String>>()
        val merges = ArrayList<IntArray>() // [r1,c1,r2,c2]

        val handler = object : DefaultHandler() {
            private var cells: HashMap<Int, String>? = null
            private var maxCol = -1
            private var curRow = -1
            private var autoRow = 0

            private var cellCol = -1
            private var cellType = ""
            private var cellStyle = -1
            private val value = StringBuilder()
            private var inValue = false
            private var inIs = false
            private var skip = 0

            override fun startElement(uri: String?, local: String?, qName: String?, a: Attributes?) {
                when ((qName ?: local ?: "").substringAfterLast(':')) {
                    "row" -> {
                        cells = HashMap()
                        maxCol = -1
                        val r = XmlSax.attr(a, "r")?.toIntOrNull()
                        curRow = if (r != null && r > 0) r - 1 else autoRow
                        autoRow = curRow + 1
                    }
                    "c" -> {
                        if (cells == null) { cells = HashMap(); curRow = autoRow; autoRow = curRow + 1 }
                        val ref = XmlSax.attr(a, "r")
                        cellCol = XmlSax.columnOf(ref)
                        if (cellCol < 0) cellCol = maxCol + 1
                        cellType = XmlSax.attr(a, "t") ?: "n"
                        cellStyle = XmlSax.attr(a, "s")?.toIntOrNull() ?: -1
                        value.setLength(0)
                        inValue = false
                        inIs = false
                        skip = 0
                    }
                    "v" -> inValue = true
                    "is" -> inIs = true
                    "rPh", "phoneticPr" -> skip++
                    "mergeCell" -> {
                        val ref = XmlSax.attr(a, "ref") ?: return
                        parseRange(ref)?.let { merges.add(it) }
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if ((inValue || inIs) && skip == 0) value.append(ch, start, length)
            }

            override fun endElement(uri: String?, local: String?, qName: String?) {
                when ((qName ?: local ?: "").substringAfterLast(':')) {
                    "v" -> inValue = false
                    "is" -> inIs = false
                    "rPh", "phoneticPr" -> if (skip > 0) skip--
                    "c" -> {
                        val text = convert(value.toString(), cellType, cellStyle, shared, dateStyles)
                        val map = cells
                        if (map != null && cellCol >= 0) {
                            if (text.isNotEmpty()) map[cellCol] = text
                            if (cellCol > maxCol) maxCol = cellCol
                        }
                        cellCol = -1
                        value.setLength(0)
                    }
                    "row" -> {
                        val map = cells
                        if (map != null) {
                            val size = if (maxCol < 0) 0 else maxCol + 1
                            val list = MutableList(size) { "" }
                            for ((k, v) in map) if (k in 0 until size) list[k] = v
                            while (curRow >= rows.size) rows.add(ArrayList())
                            rows[curRow] = list
                            cells = null
                        }
                    }
                }
            }
        }
        XmlSax.parse(bytes, handler)

        val grid = rows.map { it.toList() }
        return applyMerges(grid, merges)
    }

    /** 合并单元格：把左上角的值填满整个区域（题库列表里常用「合并分类」）。 */
    private fun applyMerges(grid: List<List<String>>, merges: List<IntArray>): List<List<String>> {
        if (merges.isEmpty()) return grid
        val out = grid.map { it.toMutableList() }.toMutableList()
        while (out.size <= merges.maxOf { it[2] }) out.add(ArrayList())
        for (m in merges) {
            val (r1, c1, r2, c2) = m
            val source = out.getOrNull(r1)?.getOrNull(c1).orEmpty()
            if (source.isEmpty()) continue
            for (r in r1..minOf(r2, out.size - 1)) {
                if (r < 0) continue
                val row = out[r]
                while (row.size <= c2) row.add("")
                for (c in c1..c2) if (c >= 0 && row[c].isEmpty()) row[c] = source
            }
        }
        return out.map { it.toList() }
    }

    private fun parseRange(ref: String): IntArray? {
        val parts = ref.split(':')
        val start = parts.getOrNull(0) ?: return null
        val end = parts.getOrNull(1) ?: start
        val r1 = XmlSax.rowOf(start); val c1 = XmlSax.columnOf(start)
        val r2 = XmlSax.rowOf(end); val c2 = XmlSax.columnOf(end)
        if (r1 < 0 || c1 < 0 || r2 < 0 || c2 < 0) return null
        return intArrayOf(minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
    }

    private fun convert(raw: String, type: String, style: Int, shared: List<String>, dateStyles: BooleanArray): String {
        if (raw.isEmpty() && type != "s") return ""
        return when (type) {
            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
            "inlineStr", "str" -> raw
            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
            "e" -> raw
            "d" -> raw
            else -> {
                val num = raw.trim()
                val d = num.toDoubleOrNull()
                if (d != null && style >= 0 && style < dateStyles.size && dateStyles[style] && d > 0 && d < 2958466) {
                    formatSerialDate(d)
                } else {
                    trimNumber(num)
                }
            }
        }
    }

    /** 1 -> "1"，1.50 -> "1.5"，避免 Excel 里 1 变成 "1.0" 影响答案比对。 */
    private fun trimNumber(s: String): String {
        if (s.isEmpty()) return s
        val d = s.toDoubleOrNull() ?: return s
        if (s.contains('e') || s.contains('E')) return s
        if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
            return d.toLong().toString()
        }
        val stripped = s.trimEnd('0').trimEnd('.')
        return if (stripped.isEmpty() || stripped == "-") s else stripped
    }

    private fun formatSerialDate(serial: Double): String {
        // Excel 1900 日期系统（含 1900-02-29 的历史 bug），序列 60 之前要减一天
        val days = serial.toInt()
        val frac = serial - days
        val epochOffset = if (days > 60) days - 1 else days
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(1899, 11, 31, 0, 0, 0)
        cal.add(java.util.Calendar.DAY_OF_YEAR, epochOffset)
        val y = cal.get(java.util.Calendar.YEAR)
        val mo = cal.get(java.util.Calendar.MONTH) + 1
        val da = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val base = "%04d-%02d-%02d".format(y, mo, da)
        val minutes = Math.round(frac * 24 * 60).toInt()
        if (minutes <= 0) return base
        return "%s %02d:%02d".format(base, minutes / 60, minutes % 60)
    }
}

class ExcelFormatException(message: String) : Exception(message)
