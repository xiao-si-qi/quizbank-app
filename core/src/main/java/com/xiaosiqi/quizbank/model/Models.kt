package com.xiaosiqi.quizbank.model

import kotlinx.serialization.Serializable

@Serializable
data class Option(val key: String, val text: String)

enum class QuestionType(val label: String) {
    SINGLE("单选题"),
    MULTIPLE("多选题"),
    JUDGE("判断题"),
    BLANK("填空题"),
    ESSAY("简答题");

    val isChoice: Boolean get() = this == SINGLE || this == MULTIPLE || this == JUDGE

    companion object {
        /** 容错识别题型，识别不出返回 null 由调用方推断。 */
        fun fromText(raw: String): QuestionType? {
            val t = raw.trim().lowercase()
                .replace(" ", "").replace("（", "(").replace("）", ")")
            if (t.isEmpty()) return null
            return when {
                t.contains("多选") || t.contains("不定项") || t.contains("多项") -> MULTIPLE
                t.contains("单选") || t.contains("单项") -> SINGLE
                // 只写「选择题」的表头不区分单多，先当单选，再由答案字母个数细分（见 QuestionParser）
                t.contains("选择") -> SINGLE
                t.contains("判断") -> JUDGE
                t.contains("填空") -> BLANK
                t.contains("简答") || t.contains("问答") || t.contains("论述") || t.contains("主观") -> ESSAY
                t == "single" || t == "radio" -> SINGLE
                t == "multiple" || t == "checkbox" || t == "multi" -> MULTIPLE
                t == "judge" || t == "boolean" || t == "bool" || t == "truefalse" -> JUDGE
                t == "blank" || t == "fill" || t == "fillblank" -> BLANK
                t == "essay" || t == "short" || t == "text" || t == "subject" -> ESSAY
                // 旧版题库用数字编码：1 单选 2 多选 3 判断 4 问答
                t == "1" -> SINGLE
                t == "2" -> MULTIPLE
                t == "3" -> JUDGE
                t == "4" -> ESSAY
                else -> null
            }
        }
    }
}

/** 一道题。options 只对选择题有意义；answers 对选择题存选项 key，对填空/简答存文本。 */
@Serializable
data class Question(
    val id: Long = 0L,
    val bankId: Long = 0L,
    val orderIndex: Int = 0,
    val type: QuestionType = QuestionType.SINGLE,
    val stem: String = "",
    val options: List<Option> = emptyList(),
    val answers: List<String> = emptyList(),
    val analysis: String = "",
    val difficulty: Int = 0,
    val chapter: String = "",
    val tags: List<String> = emptyList(),
    val score: Double = 0.0,
    val sourceRow: Int = 0,
) {
    /** 用于列表/分享的纯文本答案展示。 */
    fun answerText(): String = when (type) {
        QuestionType.SINGLE, QuestionType.MULTIPLE, QuestionType.JUDGE ->
            answers.joinToString("").ifEmpty { "—" }
        else -> answers.joinToString("  |  ").ifEmpty { "—" }
    }

    fun optionText(key: String): String = options.firstOrNull { it.key.equals(key, true) }?.text.orEmpty()
}

/** 已下载到本地、可离线练习的题库。 */
data class Bank(
    val id: Long = 0L,
    val remoteId: String = "",
    val name: String = "",
    val category: String = "",
    val description: String = "",
    val fileUrl: String = "",
    val resolvedUrl: String = "",
    val declaredCount: Int = 0,
    val version: String = "",
    val tags: List<String> = emptyList(),
    val importedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val sourceName: String = "",
    /** 题库文件所在的 alist 目录，用于把题目里的相对图片路径解析成绝对路径。 */
    val imageBase: String = "",
    /** 考试组卷配置（ExamBlueprint 的 JSON），来自题库列表 Excel。 */
    val examBlueprintJson: String = "",
)

/** 题库列表 Excel 中的一行（尚未下载）。 */
data class BankListItem(
    val remoteId: String = "",
    val name: String = "",
    val category: String = "",
    val description: String = "",
    val fileUrl: String = "",
    val declaredCount: Int = 0,
    val version: String = "",
    val tags: List<String> = emptyList(),
    val row: Int = 0,
    /** 从题库列表 Excel 里读到的考试组卷配置（题型分值/题数）。 */
    val blueprint: com.xiaosiqi.quizbank.exam.ExamBlueprint = com.xiaosiqi.quizbank.exam.ExamBlueprint(),
)

enum class PracticeMode(val label: String) {
    SEQUENTIAL("顺序练习"),
    RANDOM("随机练习"),
    WRONG("错题练习"),
    FAVORITE("收藏练习"),
}

data class BankProgress(
    val bankId: Long,
    val mode: PracticeMode = PracticeMode.SEQUENTIAL,
    val lastIndex: Int = 0,
    val answered: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    val updatedAt: Long = 0L,
)

data class BankStats(
    val bankId: Long,
    val questionCount: Int = 0,
    /** 答题次数（含重复作答）。 */
    val answered: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    /** 做过的不同题目数，用于计算完成度。 */
    val distinctAnswered: Int = 0,
    val favoriteCount: Int = 0,
    val wrongCount: Int = 0,
) {
    val accuracy: Float get() = if (answered == 0) 0f else correct.toFloat() / answered
    val progress: Float
        get() = if (questionCount == 0) 0f else (distinctAnswered.toFloat() / questionCount).coerceIn(0f, 1f)
}

data class OverallStats(
    val answered: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    val bankCount: Int = 0,
    val questionCount: Int = 0,
    val favoriteCount: Int = 0,
    val wrongCount: Int = 0,
    val days: List<DayCount> = emptyList(),
) {
    val accuracy: Float get() = if (answered == 0) 0f else correct.toFloat() / answered
}

data class DayCount(val label: String, val total: Int, val correct: Int)

data class AnswerRecord(
    val id: Long = 0L,
    val bankId: Long,
    val questionId: Long,
    val correct: Boolean?,
    val userAnswer: String,
    val answeredAt: Long,
    val mode: PracticeMode,
)
