package com.xiaosiqi.quizbank.importer

import com.xiaosiqi.quizbank.excel.SheetTable

/**
 * 表头识别。
 *
 * 现实里用户的 Excel 表头千奇百怪：「题干」「题目」「试题」「问题」都可能出现，
 * 顺序也不固定，还可能在前面多几列无关内容。所以这里不写死列号，
 * 而是先做「别名精确匹配」，再做「包含匹配」，最后对选项列做正则专项识别。
 */
object ColumnMatcher {

    data class Field(
        val key: String,
        val aliases: List<String>,
        /**
         * 归一化后的表头命中这个正则时，该字段不参与匹配。
         * 典型场景：「答案」字段不能用 contains("answer") 去认领 `answer_A` 这种选项列，
         * 否则会把选项 A 的文本当成正确答案（错的还很隐蔽）。
         */
        val reject: Regex? = null,
        /**
         * 允许多列归到同一字段（用 [Mapping.cols] 取全部）。
         * 适合「标签 / 考点 / 参考资料」这类本来就可能有多列的信息。
         */
        val multi: Boolean = false,
    )

    data class Mapping(
        val headerRow: Int,
        val columns: Map<String, Int>,
        val multiColumns: Map<String, List<Int>> = emptyMap(),
    ) {
        fun col(key: String): Int = columns[key] ?: -1

        fun has(key: String): Boolean = columns.containsKey(key)

        /** 该字段命中的全部列（单列字段返回 0 或 1 个）。 */
        fun cols(key: String): List<Int> = multiColumns[key] ?: listOfNotNull(columns[key])

        operator fun get(key: String): Int = col(key)
    }

    /**
     * 在表头前 [maxScan] 行里找出最像表头的一行并建立列映射。
     * 判定标准：命中字段数最多（至少 2 个，且必须命中 required 之一）。
     */
    fun detect(
        sheet: SheetTable,
        fields: List<Field>,
        requiredAny: Set<String>,
        maxScan: Int = 8,
    ): Mapping? {
        var best: Mapping? = null
        var bestScore = 0
        val limit = minOf(maxScan, sheet.rowCount)
        for (r in 0 until limit) {
            val row = sheet.row(r)
            if (row.none { it.isNotBlank() }) continue
            // 表头行不会长这样：含路径分隔符或网址的行一定是数据行
            if (row.any { looksLikeData(it) }) continue
            val mapping = match(row, fields) ?: continue
            val score = mapping.columns.size
            val requiredHit = mapping.columns.keys.any { it in requiredAny }
            if (!requiredHit) continue
            if (score > bestScore) {
                bestScore = score
                best = Mapping(r, mapping.columns, mapping.multiColumns)
            }
        }
        return best
    }

    private fun looksLikeData(cell: String): Boolean {
        val text = cell.trim()
        if (text.isEmpty()) return false
        if (text.contains('/') || text.contains('\\')) return true
        return text.startsWith("http", true)
    }

    private data class Candidate(val field: String, val col: Int, val strength: Int)

    private fun match(header: List<String>, fields: List<Field>): Mapping? {
        val normalized = header.map { TextNorm.header(it) }
        val candidates = ArrayList<Candidate>()

        for (field in fields) {
            for ((col, text) in normalized.withIndex()) {
                if (text.isEmpty()) continue
                if (field.reject?.matches(text) == true) continue
                var strength = 0
                for (alias in field.aliases) {
                    val a = TextNorm.header(alias)
                    if (a.isEmpty()) continue
                    strength = maxOf(
                        strength,
                        when {
                            text == a -> 3
                            text.startsWith(a) || text.endsWith(a) -> 2
                            text.contains(a) -> 1
                            else -> 0
                        },
                    )
                }
                if (strength > 0) candidates.add(Candidate(field.key, col, strength))
            }
        }
        if (candidates.isEmpty()) return null

        // 强匹配优先，且一列只归属一个字段；多列字段（如标签）可以吃掉多列
        val takenCols = HashSet<Int>()
        val takenFields = HashSet<String>()
        val result = LinkedHashMap<String, Int>()
        val multiResult = LinkedHashMap<String, MutableList<Int>>()
        for (c in candidates.sortedWith(compareByDescending<Candidate> { it.strength }.thenBy { it.col })) {
            if (c.col in takenCols) continue
            val field = fields.firstOrNull { it.key == c.field } ?: continue
            if (!field.multi && c.field in takenFields) continue
            takenCols.add(c.col)
            takenFields.add(c.field)
            if (!result.containsKey(c.field)) result[c.field] = c.col
            multiResult.getOrPut(c.field) { ArrayList() }.add(c.col)
        }
        return if (result.isEmpty()) null else Mapping(0, result, multiResult)
    }

    /** 选项列专项识别：选项A / A / 选项1 / OptionC / answer_A / 答案A 等。 */
    fun detectOptionColumns(header: List<String>, exclude: Set<Int> = emptySet()): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val patterns = listOf(
            Regex("^(?:选项|选择|答案选项|答案|option|opt|answer)([a-h])$"),
            Regex("^(?:选项|选择|答案|answer)(\\d{1,2})$"),
            Regex("^([a-h])(?:项|选项)?(?:内容|文本|value)?$"),
        )
        for ((col, raw) in header.withIndex()) {
            if (col in exclude || col in out.values) continue
            val text = TextNorm.header(raw)
            if (text.isEmpty() || text.length > 12) continue
            for (p in patterns) {
                val m = p.find(text) ?: continue
                val g = m.groupValues[1]
                val key = when {
                    g.length == 1 && g[0] in 'a'..'h' -> g.uppercase()
                    g.toIntOrNull() != null && g.toInt() in 1..26 -> ('A' + g.toInt() - 1).toString()
                    else -> continue
                }
                if (!out.containsKey(key)) out[key] = col
                break
            }
        }
        return out
    }
}
