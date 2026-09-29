package com.xiaosiqi.quizbank.importer

import com.xiaosiqi.quizbank.excel.SheetTable
import com.xiaosiqi.quizbank.model.Option
import com.xiaosiqi.quizbank.model.Question
import com.xiaosiqi.quizbank.model.QuestionType

/**
 * 题库文件解析。
 *
 * 设计原则：宁可能用也不要报错。用户手里的 Excel 是历史积累的，
 * 表头不统一、题型写在题干里、选项挤在一格、答案是「对/√/T」……
 * 这些都必须在导入阶段被消化掉，而不是丢给用户一条报错。
 */
object QuestionParser {

    data class Result(
        val questions: List<Question>,
        val warnings: List<String>,
        val skipped: Int,
    ) {
        val count: Int get() = questions.size
    }

    /** 形如 `answer_A`、`答案A`、`选项1` 的表头属于「选项列」，不能被「答案」字段抢走。 */
    private val OPTION_LIKE_HEADER = Regex("^(?:选项|选择|答案|answer|opt|option)s?([a-h]|\\d{1,2})$")

    private val FIELDS = listOf(
        ColumnMatcher.Field("no", listOf("题号", "序号", "编号", "no", "index", "id")),
        ColumnMatcher.Field("type", listOf("题型", "类型", "题目类型", "试题类型", "题目类别", "type")),
        ColumnMatcher.Field(
            "stem",
            listOf("题干", "题目", "试题", "问题", "题目内容", "题目描述", "题目正文", "内容", "question", "stem", "title"),
        ),
        ColumnMatcher.Field(
            "options",
            listOf("选项", "备选项", "选项列表", "choices", "options"),
            reject = OPTION_LIKE_HEADER,
        ),
        ColumnMatcher.Field(
            "answer",
            listOf("答案", "正确答案", "参考答案", "标准答案", "answer", "key"),
            reject = OPTION_LIKE_HEADER,
        ),
        ColumnMatcher.Field(
            "analysis",
            listOf("解析", "答案解析", "试题解析", "题目解析", "解答", "说明", "explain", "analysis"),
        ),
        ColumnMatcher.Field("difficulty", listOf("难度", "难度系数", "难易度", "difficulty", "level")),
        ColumnMatcher.Field(
            "chapter",
            listOf(
                "章节", "科目", "知识点", "所属章节", "题库分类", "分类",
                "考察内容", "考察范围", "examination_scope", "scope",
                "chapter", "section", "knowledge",
            ),
        ),
        ColumnMatcher.Field(
            "tags",
            listOf(
                "标签", "标记", "考点", "tags", "tag",
                // 旧版题库的这两列在新版里没有对应概念，放进标签里以免信息丢失
                "reference", "参考资料", "important", "重要级别",
            ),
            multi = true,
        ),
        ColumnMatcher.Field("score", listOf("分值", "分数", "得分", "score", "point")),
    )

    private const val MAX_WARNINGS = 40

    private val STRICT_OPTION_LINE = Regex("^\\s*[（(]?([A-Ha-h1-9])[）)]?\\s*[.、．)）:：]\\s*(\\S.*)$")
    private val SPACED_OPTION_LINE = Regex("^\\s*([A-Ha-h])[\\t ]+(\\S.*)$")
    private val TYPE_PREFIX = Regex("^\\s*[【\\[（(]?\\s*(单项选择题|多项选择题|不定项选择题|判断题|填空题|简答题|单选题|多选题|选择题|判断题|判断题)\\s*[题类]?\\s*[】\\]）)]\\s*")

    private val TRUE_WORDS = setOf("对", "正确", "是", "√", "✓", "v", "t", "true", "y", "yes", "right", "1", "a")
    private val FALSE_WORDS = setOf("错", "错误", "否", "×", "x", "f", "false", "n", "no", "wrong", "0", "b")

    /**
     * 用来「推断」题型的对错词，刻意去掉了 1/0/a/b ——
     * 填空题答案是 0 或 1 很常见，不能因此判成判断题。
     */
    private val JUDGE_HINT_WORDS = setOf(
        "对", "正确", "是", "√", "✓", "t", "true", "y", "yes", "right",
        "错", "错误", "否", "×", "f", "false", "n", "no", "wrong",
    )

    /** 解析整本工作簿（可能包含「单选题」「多选题」等多个 sheet）。 */
    fun parseWorkbook(sheets: List<SheetTable>): Result {
        val all = ArrayList<Question>()
        val warnings = ArrayList<String>()
        var skipped = 0
        var parsedAny = false

        for (sheet in sheets) {
            val single = parseSheet(sheet, sheetHintType(sheet.name), warnings)
            if (single.questions.isEmpty()) {
                if (parsedAny) continue
                if (sheets.size > 1) continue
            }
            if (single.questions.isNotEmpty()) parsedAny = true
            all.addAll(single.questions)
            skipped += single.skipped
        }

        if (all.isEmpty()) {
            val sheetInfo = sheets.joinToString("、") { "「${it.name}」${it.rowCount}行" }
            throw ImportException(
                "没有识别出任何题目。已读取工作表：$sheetInfo\n\n" +
                    "请确认表头包含「题干」或「题目」列；答案列建议命名为「答案」。\n" +
                    "可以直接用「设置 → 导出 Excel 模板」生成的标准模板。"
            )
        }
        all.forEachIndexed { i, q -> all[i] = q.copy(orderIndex = i) }
        return Result(all, warnings.take(MAX_WARNINGS), skipped)
    }

    /** 解析单个工作表。 */
    fun parseSheet(
        sheet: SheetTable,
        hintType: QuestionType? = null,
        warnings: MutableList<String> = ArrayList(),
    ): Result {
        val mapping = ColumnMatcher.detect(sheet, FIELDS, requiredAny = setOf("stem"))
        if (mapping == null) return Result(emptyList(), warnings, 0)

        val headerRow = mapping.headerRow
        val headerCells = sheet.row(headerRow)
        // 先找「选项A/选项B…」这种分列写法；只有分列认不出来时才用「选项」合并列，
        // 否则「选项A」会被「选项」这个别名抢走，导致只剩一个选项。
        val optionCols = ColumnMatcher.detectOptionColumns(
            headerCells,
            exclude = mapping.columns.values.toSet() - setOf(mapping["options"]),
        )
        val combinedCol = if (optionCols.size >= 2) -1 else mapping["options"]
        val stemCol = mapping["stem"]

        val questions = ArrayList<Question>()
        var skipped = 0

        for ((rowIndex, row) in sheet.nonEmptyRows()) {
            if (rowIndex <= headerRow) continue
            val excelRow = rowIndex + 1
            val rawStem = TextNorm.value(row.getOrElse(stemCol) { "" })
            if (rawStem.isBlank()) {
                if (row.any { it.isNotBlank() }) skipped++
                continue
            }

            var stem = rawStem
            var explicitType = mapping["type"].takeIf { it >= 0 }
                ?.let { QuestionType.fromText(row.getOrElse(it) { "" }) }
            if (explicitType == null) {
                TYPE_PREFIX.find(stem)?.let { m ->
                    QuestionType.fromText(m.groupValues[1])?.let { t ->
                        explicitType = t
                        stem = stem.removeRange(m.range).trim()
                    }
                }
            }

            var options = buildOptions(row, optionCols, combinedCol)
            if (options.isEmpty()) {
                val (rest, embedded) = splitEmbeddedOptions(stem)
                if (embedded.size >= 2) {
                    options = embedded
                    stem = rest.ifBlank { stem }
                }
            }

            val rawAnswer = mapping["answer"].takeIf { it >= 0 }?.let { TextNorm.value(row.getOrElse(it) { "" }) }.orEmpty()
            // 题型判定优先级：表里写明的题型 > 工作表名暗示的题型 > 根据选项/答案推断
            val hinted = hintType?.takeIf { it == QuestionType.JUDGE || options.isEmpty() }
            val type = refineChoice(
                explicitType
                    ?: hinted
                    ?: inferType(options, rawAnswer, stem, hintType)
                    ?: QuestionType.SINGLE,
                rawAnswer,
            )

            var finalOptions = options
            if (type == QuestionType.JUDGE && finalOptions.isEmpty()) {
                finalOptions = listOf(Option("A", "正确"), Option("B", "错误"))
            }
            val answers = parseAnswers(rawAnswer, type, finalOptions)

            if (rawAnswer.isBlank() && questions.size < 3) {
                warnings.add("第 $excelRow 行没有答案，导入后需自行判断对错")
            }

            questions.add(
                Question(
                    orderIndex = questions.size,
                    type = type,
                    stem = stem,
                    options = finalOptions,
                    answers = answers,
                    analysis = mapping["analysis"].takeIf { it >= 0 }?.let { TextNorm.value(row.getOrElse(it) { "" }) }.orEmpty(),
                    difficulty = mapping["difficulty"].takeIf { it >= 0 }?.let { parseDifficulty(row.getOrElse(it) { "" }) } ?: 0,
                    chapter = mapping["chapter"].takeIf { it >= 0 }?.let { TextNorm.oneLine(row.getOrElse(it) { "" }) }.orEmpty(),
                    tags = mapping.cols("tags")
                        .flatMap { col -> TextNorm.splitList(row.getOrElse(col) { "" }) }
                        .distinct(),
                    score = mapping["score"].takeIf { it >= 0 }?.let { TextNorm.toDoubleOrNull(row.getOrElse(it) { "" }) } ?: 0.0,
                    sourceRow = excelRow,
                )
            )
        }

        if (skipped > 0) warnings.add("「${sheet.name}」有 $skipped 行题干为空，已跳过")
        return Result(questions, warnings, skipped)
    }

    // ------------------------------------------------------------------ 选项

    private fun buildOptions(
        row: List<String>,
        optionCols: Map<String, Int>,
        combinedCol: Int,
    ): List<Option> {
        val fromCols = optionCols.entries
            .mapNotNull { (key, col) ->
                val text = TextNorm.oneLine(row.getOrElse(col) { "" })
                if (text.isEmpty()) null else Option(key, text)
            }
            .sortedBy { it.key }
        if (fromCols.size >= 2) return fromCols

        if (combinedCol >= 0) {
            val combined = TextNorm.value(row.getOrElse(combinedCol) { "" })
            val parsed = parseOptionBlock(combined)
            if (parsed.size >= 2) return parsed
            if (parsed.size == 1 && fromCols.isEmpty()) return parsed
        }
        // 只有一列选项时也算数（例如「选项」列里只放了 A、B 两项）
        return if (fromCols.isNotEmpty()) fromCols else emptyList()
    }

    /** 从「A. xx B. yy」这类文本块中拆选项；要求字母连续，避免把正文误判成选项。 */
    fun parseOptionBlock(text: String): List<Option> {
        if (text.isBlank()) return emptyList()
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 2) {
            val strict = lines.mapNotNull { line ->
                STRICT_OPTION_LINE.find(line)?.let { normalizeKey(it.groupValues[1]) to it.groupValues[2].trim() }
            }
            if (isSequential(strict.map { it.first }) && strict.size >= 2) return toOptions(strict)

            val spaced = lines.mapNotNull { line ->
                SPACED_OPTION_LINE.find(line)?.let { normalizeKey(it.groupValues[1]) to it.groupValues[2].trim() }
            }
            if (isSequential(spaced.map { it.first }) && spaced.size >= 2) return toOptions(spaced)
        }
        // 单行内的 A. xx B. yy
        val matches = Regex("[（(]?([A-Ha-h])[）)]?\\s*[.、．)）]\\s*").findAll(text).toList()
        if (matches.size >= 2) {
            val pairs = ArrayList<Pair<String, String>>()
            matches.forEachIndexed { i, m ->
                val start = m.range.last + 1
                val end = matches.getOrNull(i + 1)?.range?.first ?: text.length
                pairs.add(normalizeKey(m.groupValues[1]) to text.substring(start, end).trim())
            }
            if (isSequential(pairs.map { it.first })) return toOptions(pairs)
        }
        return emptyList()
    }

    private fun toOptions(pairs: List<Pair<String, String>>): List<Option> =
        pairs.map { Option(it.first, it.second) }.sortedBy { it.key }

    /** 「1」→「A」，让「1. xxx 2. yyy」这种编号也能当选项。 */
    private fun normalizeKey(raw: String): String {
        val s = raw.trim()
        val n = s.toIntOrNull()
        if (n != null && n in 1..26) return ('A' + n - 1).toString()
        return s.uppercase()
    }

    private fun isSequential(keys: List<String>): Boolean {
        if (keys.size < 2) return false
        val letters = keys.all { it.length == 1 && it[0] in 'A'..'H' }
        if (!letters) return false
        return keys.withIndex().all { (i, k) -> k[0] == 'A' + i }
    }

    /** 题干里内嵌了选项时，把选项拆出来，剩下的作为题干。 */
    fun splitEmbeddedOptions(stem: String): Pair<String, List<Option>> {
        if (!stem.contains('\n')) return stem to emptyList()
        val lines = stem.split('\n')
        val firstOption = lines.indexOfFirst { line ->
            STRICT_OPTION_LINE.matches(line.trim()) || SPACED_OPTION_LINE.matches(line.trim())
        }
        if (firstOption <= 0) return stem to emptyList()
        val optionBlock = lines.subList(firstOption, lines.size).joinToString("\n")
        val options = parseOptionBlock(optionBlock)
        if (options.size < 2) return stem to emptyList()
        return lines.subList(0, firstOption).joinToString("\n").trim() to options
    }

    // ------------------------------------------------------------------ 答案

    /**
     * 表头只写「选择题」时，它既不等于单选也不等于多选。
     * 这里按答案里字母的个数细分：`B` → 单选，`ABD` → 多选。
     */
    private fun refineChoice(type: QuestionType, rawAnswer: String): QuestionType {
        if (type != QuestionType.SINGLE) return type
        val compact = rawAnswer.trim().replace(Regex("[\\s、,，|/]+"), "")
        val pureLetters = compact.length >= 2 && compact.all { it.uppercaseChar() in 'A'..'H' }
        return if (pureLetters) QuestionType.MULTIPLE else type
    }

    private fun inferType(options: List<Option>, rawAnswer: String, stem: String, hint: QuestionType?): QuestionType? {
        if (options.size == 2 && options.all { isTrueWord(it.text) || isFalseWord(it.text) } &&
            (isTrueWord(rawAnswer) || isFalseWord(rawAnswer))
        ) {
            return QuestionType.JUDGE
        }
        if (options.isNotEmpty()) {
            val letters = letterTokens(rawAnswer)
            return if (letters != null && letters.size > 1) QuestionType.MULTIPLE else hint ?: QuestionType.SINGLE
        }
        if (rawAnswer.isBlank()) return null
        // 没有选项、答案又是一个明确的对错词 —— 那就是判断题
        if (TextNorm.loose(rawAnswer) in JUDGE_HINT_WORDS) return QuestionType.JUDGE
        val parts = splitBlankAnswer(rawAnswer)
        return when {
            parts.size > 1 -> QuestionType.BLANK
            looksLikeEssay(stem, rawAnswer) -> QuestionType.ESSAY
            else -> QuestionType.BLANK
        }
    }

    /** 简答题的启发式：题干本身在要求「论述/简述/说明」，或者答案明显是一段话。 */
    private fun looksLikeEssay(stem: String, answer: String): Boolean {
        if (answer.length > 18) return true
        val keywords = listOf("简答", "简述", "论述", "试述", "说明", "谈谈", "为什么", "分析", "阐述", "举例说明")
        return keywords.any { stem.contains(it) }
    }

    fun parseAnswers(raw: String, type: QuestionType, options: List<Option>): List<String> {
        if (raw.isBlank()) return emptyList()
        return when (type) {
            QuestionType.BLANK -> splitBlankAnswer(raw)
            QuestionType.ESSAY -> listOf(TextNorm.value(raw))
            else -> parseChoiceAnswers(raw, options)
        }
    }

    private fun splitBlankAnswer(raw: String): List<String> =
        TextNorm.value(raw).split(Regex("\\s*[|｜]\\s*|\\s*[；;]\\s*|\\s*\\n\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(TextNorm.value(raw)) }

    private fun parseChoiceAnswers(raw: String, options: List<Option>): List<String> {
        val cleaned = TextNorm.value(raw)
        val optionKeys = options.map { it.key.uppercase() }.toSet()
        val judgeLike = isTrueWord(cleaned) || isFalseWord(cleaned)
        letterTokens(cleaned)?.let { letters ->
            // 「F」既是判断题的「错」，也可能是选项 F。
            // 只有当选项里真的没有这个字母时，才把它当判断题答案。
            val ambiguousWithJudge = judgeLike && letters.none { it in optionKeys }
            if (letters.isNotEmpty() && !ambiguousWithJudge) return letters
        }
        if (isTrueWord(cleaned)) return listOf(judgeKey(true, options))
        if (isFalseWord(cleaned)) return listOf(judgeKey(false, options))
        // 答案是选项文本本身
        val byText = options.filter { TextNorm.loose(it.text) == TextNorm.loose(cleaned) }
        if (byText.size == 1) return listOf(byText[0].key)
        // 多个答案拼成一行，逐个匹配选项文本
        val parts = TextNorm.splitList(cleaned)
        if (parts.size > 1) {
            val keys = parts.mapNotNull { p ->
                options.firstOrNull { TextNorm.loose(it.text) == TextNorm.loose(p) }?.key
            }
            if (keys.size == parts.size && keys.isNotEmpty()) return keys
        }
        return listOf(cleaned)
    }

    private fun judgeKey(trueValue: Boolean, options: List<Option>): String {
        val wanted = if (trueValue) TRUE_WORDS else FALSE_WORDS
        options.firstOrNull { TextNorm.loose(it.text) in wanted }?.let { return it.key }
        return if (options.any { it.key == "B" }) (if (trueValue) "A" else "B") else options.firstOrNull()?.key ?: "A"
    }

    /** 提取形如 A / AB / A,B / A、B 的选项字母；不是纯字母组合则返回 null。 */
    fun letterTokens(raw: String): List<String>? {
        val s = TextNorm.toHalfWidth(raw).trim()
        if (s.isEmpty() || s.length > 24) return null
        val stripped = s.replace(Regex("[\\s,，、;；/|]+"), "")
        if (stripped.isEmpty()) return null
        if (!stripped.all { it in 'A'..'H' || it in 'a'..'h' }) return null
        return stripped.uppercase().map { it.toString() }.distinct().sorted()
    }

    private fun isTrueWord(raw: String): Boolean = TextNorm.loose(raw) in TRUE_WORDS
    private fun isFalseWord(raw: String): Boolean = TextNorm.loose(raw) in FALSE_WORDS

    private fun parseDifficulty(raw: String): Int {
        val d = TextNorm.toDoubleOrNull(raw) ?: return 0
        return when {
            d <= 0 -> 0
            // 小于 1 才当作百分比（0.3 → 5 档里的 2 档）；
            // 正好等于 1 是「1 级」，不能换算成 5 档。
            d < 1.0 -> Math.round(d * 5).toInt().coerceIn(1, 5)
            else -> d.toInt().coerceIn(1, 5)
        }
    }

    /** 工作表名本身就是题型提示（「单选题」「判断题」）。 */
    private fun sheetHintType(name: String): QuestionType? = QuestionType.fromText(name)
}

class ImportException(message: String) : Exception(message)
