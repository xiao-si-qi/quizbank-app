package com.xiaosiqi.quizbank.exam

import com.xiaosiqi.quizbank.model.QuestionType
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次考试里某一道题的记录。
 *
 * 为什么要把题干/选项/答案都存下来（而不是只存 questionId）：
 * 题库随时可能重新导入或更新，只存 id 的话历史考试记录就对不上了。
 * 快照一份，考试记录永远能原样回顾。
 */
@Serializable
data class ExamDetail(
    /** 第几题（1 基） */
    val index: Int,
    val type: String = "",
    /** 本题分值 */
    val score: Int = 0,
    /** 是否正确；null 表示未作答或主观题自评前 */
    val correct: Boolean? = null,
    /** 我的作答（选择题是选项字母，如 ABD） */
    val my: String = "",
    /** 正确答案 */
    val answer: String = "",
    val stem: String = "",
    val analysis: String = "",
    /** 选项文本，按 A/B/C… 顺序 */
    val options: List<String> = emptyList(),
)

/** 构造一次考试记录所需的外部信息。 */
data class ExamMeta(
    val user: String = "",
    val bankId: Long = 0L,
    val bankName: String = "",
    val bankKey: String = "",
    val category: String = "",
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val device: String = "",
    val appVersion: String = "1.0.0",
)

/**
 * 一次考试的成绩，同时是上传到 alist 的 JSON 格式。
 * 只有考试模式才会生成这个东西；普通练习只记错题，不产生成绩。
 */
@Serializable
data class ExamRecord(
    val schema: Int = 2,
    val app: String = "QuizBank",
    val appVersion: String = "1.0.0",
    val kind: String = KIND_EXAM,
    val user: String = "",
    /** 本地题库 id（0 表示来自云端记录）。 */
    val bankId: Long = 0L,
    val bankName: String = "",
    val bankKey: String = "",
    val category: String = "",
    val questionCount: Int = 0,
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    val selfGraded: Int = 0,
    val answered: Int = 0,
    /** 试卷满分（由题型分值配置决定，通常是 100） */
    val totalPoints: Int = 0,
    /** 实际得分 */
    val scoredPoints: Int = 0,
    /** 百分制得分 */
    val percent: Int = 0,
    val durationSec: Int = 0,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val device: String = "",
    val details: List<ExamDetail> = emptyList(),
    @Transient val localId: Long = 0L,
    @Transient val remotePath: String = "",
    @Transient val uploaded: Boolean = false,
) {
    val durationText: String
        get() {
            val m = durationSec / 60
            val s = durationSec % 60
            return if (m > 0) "${m}分${s}秒" else "${s}秒"
        }

    fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date(if (finishedAt > 0) finishedAt else System.currentTimeMillis()))
        val safeUser = user.safeSegment().take(20).ifBlank { "匿名" }
        val safeBank = bankName.safeSegment().take(40).ifBlank { "题库" }
        return "${stamp}_${safeUser}_${safeBank}.json"
    }

    companion object {
        const val KIND_EXAM = "EXAM"

        private fun String.safeSegment(): String = trim().map { ch ->
            when {
                ch.isLetterOrDigit() || ch == '-' || ch == '_' -> ch
                ch.code > 127 -> ch
                else -> '_'
            }
        }.joinToString("").replace(Regex("_+"), "_").trim('_')

        /** 把一套卷子 + 作答结果整理成可保存/可上传的记录。 */
        fun from(
            paper: ExamPaper,
            results: Map<Int, Boolean?>,
            userAnswers: Map<Int, List<String>>,
            meta: ExamMeta,
        ): ExamRecord {
            val summary = ExamBuilder.score(paper, results)
            val details = paper.items.mapIndexed { index, item ->
                ExamDetail(
                    index = index + 1,
                    type = item.question.type.label,
                    score = item.score,
                    correct = results[index],
                    my = userAnswers[index]?.joinToString("|").orEmpty(),
                    answer = item.question.answerText(),
                    stem = item.question.stem,
                    analysis = item.question.analysis,
                    options = item.question.options.map { "${it.key}. ${it.text}" },
                )
            }
            val duration = ((meta.finishedAt - meta.startedAt).coerceAtLeast(0) / 1000).toInt()
            return ExamRecord(
                appVersion = meta.appVersion,
                user = meta.user,
                bankId = meta.bankId,
                bankName = meta.bankName,
                bankKey = meta.bankKey,
                category = meta.category,
                questionCount = paper.questionCount,
                correctCount = summary.correctCount,
                wrongCount = summary.wrongCount,
                selfGraded = summary.selfGradedCount,
                answered = summary.answeredCount,
                totalPoints = summary.totalPoints,
                scoredPoints = summary.scoredPoints,
                percent = summary.percent,
                durationSec = duration,
                startedAt = meta.startedAt,
                finishedAt = meta.finishedAt,
                device = meta.device,
                details = details,
            )
        }
    }
}

object ExamJson {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(record: ExamRecord): String = json.encodeToString(ExamRecord.serializer(), record)

    fun decode(text: String): ExamRecord = json.decodeFromString(ExamRecord.serializer(), text)

    fun decodeOrNull(text: String): ExamRecord? = runCatching { decode(text) }.getOrNull()

    // 组卷配置跟着题库一起存进本地库
    fun encodeBlueprint(blueprint: ExamBlueprint): String =
        json.encodeToString(ExamBlueprint.serializer(), blueprint)

    fun decodeBlueprint(text: String?): ExamBlueprint {
        if (text.isNullOrBlank()) return ExamBlueprint()
        return runCatching { json.decodeFromString(ExamBlueprint.serializer(), text) }
            .getOrDefault(ExamBlueprint())
    }
}

/** 题库上的组卷配置（延迟解析）。 */
val com.xiaosiqi.quizbank.model.Bank.examBlueprint: ExamBlueprint
    get() = ExamJson.decodeBlueprint(examBlueprintJson)
