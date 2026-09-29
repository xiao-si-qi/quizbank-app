package com.xiaosiqi.quizbank.importer

import com.xiaosiqi.quizbank.excel.SheetTable
import com.xiaosiqi.quizbank.exam.ExamBlueprint
import com.xiaosiqi.quizbank.model.QuestionType
import com.xiaosiqi.quizbank.model.BankListItem

/**
 * 「题库列表」Excel 解析：这张表是 alist 上的目录，每行指向一个题库文件。
 */
object BankListParser {

    data class Result(
        val items: List<BankListItem>,
        val warnings: List<String>,
    )

    private val FIELDS = listOf(
        ColumnMatcher.Field("id", listOf("题库id", "id", "编号", "标识", "标识符")),
        ColumnMatcher.Field("name", listOf("题库名称", "名称", "题库", "标题", "名字", "name", "title")),
        ColumnMatcher.Field("category", listOf("分类", "类别", "科目", "题库分类", "category", "group")),
        ColumnMatcher.Field(
            "description",
            listOf("描述", "简介", "说明", "介绍", "备注", "description", "desc", "remark", "memo"),
        ),
        ColumnMatcher.Field(
            "url",
            listOf("文件地址", "题库地址", "下载地址", "地址", "链接", "路径", "文件", "url", "path", "file", "link"),
        ),
        ColumnMatcher.Field("count", listOf("题目数量", "题数", "题目数", "数量", "总数", "count", "total")),
        ColumnMatcher.Field("version", listOf("版本", "版本号", "version")),
        ColumnMatcher.Field("tags", listOf("标签", "tags", "tag")),
        // ---- 考试组卷配置（都可选）----
        ColumnMatcher.Field("exam_count", listOf("考试题数", "试卷题数", "题数上限", "exam_count", "examcount")),
        ColumnMatcher.Field("exam_plan", listOf("组卷", "试卷结构", "组卷规则", "exam_plan", "examplan")),
        ColumnMatcher.Field("score_single", listOf("单选题分", "单选分值", "单选分数", "single_score")),
        ColumnMatcher.Field("score_multiple", listOf("多选题分", "多选分值", "多选分数", "multiple_score")),
        ColumnMatcher.Field("score_judge", listOf("判断题分", "判断分值", "判断分数", "judge_score")),
        ColumnMatcher.Field("score_blank", listOf("填空题分", "填空分值", "填空分数", "blank_score")),
        ColumnMatcher.Field("score_essay", listOf("简答题分", "简答分值", "简答分数", "essay_score")),
    )

    fun parse(sheet: SheetTable): Result {
        val warnings = ArrayList<String>()
        val mapping = ColumnMatcher.detect(sheet, FIELDS, requiredAny = setOf("url", "name"))
            ?: throw ImportException(
                "没有识别出「题库列表」的表头。\n\n" +
                    "第一行需要至少包含「题库名称」和「文件地址」两列。\n" +
                    "当前第一行内容：" + sheet.row(0).take(8).joinToString(" | ").ifBlank { "（空）" }
            )

        val headerRow = mapping.headerRow
        var urlCol = mapping["url"]
        if (urlCol < 0 || mapping["name"] == urlCol) urlCol = detectUrlColumnByContent(sheet, headerRow)
        if (urlCol < 0) throw ImportException("没有找到「文件地址」列，请检查题库列表的表头。")

        val nameCol = mapping["name"].takeIf { it >= 0 && it != urlCol }
        val items = ArrayList<BankListItem>()
        val seen = HashSet<String>()
        var duplicates = 0

        for ((rowIndex, row) in sheet.nonEmptyRows()) {
            if (rowIndex <= headerRow) continue
            val url = TextNorm.value(row.getOrElse(urlCol) { "" }).replace(" ", "")
            if (url.isBlank()) continue
            if (!seen.add(url.lowercase())) { duplicates++; continue }

            val name = nameCol?.let { TextNorm.oneLine(row.getOrElse(it) { "" }) }.orEmpty()
                .ifBlank { nameFromUrl(url) }

            items.add(
                BankListItem(
                    remoteId = mapping["id"].takeIf { it >= 0 }?.let { TextNorm.oneLine(row.getOrElse(it) { "" }) }.orEmpty(),
                    name = name,
                    category = mapping["category"].takeIf { it >= 0 }?.let { TextNorm.oneLine(row.getOrElse(it) { "" }) }.orEmpty(),
                    description = mapping["description"].takeIf { it >= 0 }?.let { TextNorm.oneLine(row.getOrElse(it) { "" }) }.orEmpty(),
                    fileUrl = url,
                    declaredCount = mapping["count"].takeIf { it >= 0 }?.let { TextNorm.toIntOrNull(row.getOrElse(it) { "" }) } ?: 0,
                    version = mapping["version"].takeIf { it >= 0 }?.let { TextNorm.oneLine(row.getOrElse(it) { "" }) }.orEmpty(),
                    tags = mapping["tags"].takeIf { it >= 0 }
                        ?.let { TextNorm.splitList(row.getOrElse(it) { "" }) } ?: emptyList(),
                    row = rowIndex + 1,
                    blueprint = parseBlueprint(mapping, row),
                )
            )
        }

        if (items.isEmpty()) throw ImportException("题库列表里没有有效的文件地址行。")
        if (duplicates > 0) warnings.add("有 $duplicates 行地址重复，已自动去重")
        return Result(items, warnings)
    }

    /** 表头不认识时，靠内容特征找「地址列」：含 / 或常见后缀。 */
    private fun detectUrlColumnByContent(sheet: SheetTable, headerRow: Int): Int {
        val dataRows = sheet.nonEmptyRows().filter { it.first > headerRow }.take(30)
        if (dataRows.isEmpty()) return -1
        val cols = sheet.columnCount()
        var bestCol = -1
        var bestHits = 0
        for (c in 0 until cols) {
            val hits = dataRows.count { (_, row) ->
                val v = row.getOrNull(c).orEmpty().trim()
                v.contains('/') || v.endsWith(".xlsx", true) || v.endsWith(".xls", true) ||
                    v.endsWith(".csv", true) || v.startsWith("http")
            }
            if (hits > bestHits) { bestHits = hits; bestCol = c }
        }
        return if (bestHits >= maxOf(1, dataRows.size / 2)) bestCol else -1
    }

    /** 从同一行里读出考试组卷配置；没有配置就返回默认值。 */
    private fun parseBlueprint(mapping: ColumnMatcher.Mapping, row: List<String>): ExamBlueprint {
        fun cell(key: String): String? =
            mapping[key].takeIf { it >= 0 }?.let { row.getOrElse(it) { "" } }?.takeIf { it.isNotBlank() }

        val scoreCells = QuestionType.entries.associateWith { type ->
            cell("score_" + type.name.lowercase()).orEmpty()
        }
        return ExamBlueprint.parse(
            questionCount = cell("exam_count"),
            scoreCells = scoreCells,
            plan = cell("exam_plan"),
        )
    }

    fun nameFromUrl(url: String): String {
        val clean = url.substringBefore('?').trimEnd('/')
        val file = clean.substringAfterLast('/')
        return file.substringBeforeLast('.').ifBlank { clean }
    }
}
