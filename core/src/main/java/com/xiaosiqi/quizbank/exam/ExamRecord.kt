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
    /** 选项文本（不含字母前缀），顺序与 [optionKeys] 对应 */
    val options: List<String> = emptyList(),
    /** 选项字母，如 [A, B, C, D]；续考时靠它重建题目 */
    val optionKeys: List<String> = emptyList(),
    /** 正确答案（判分用）：选择题是字母，填空题是答案文本 */
    val answerKeys: List<String> = emptyList(),
    /** 是否已经看过答案（没作答也可能看过） */
    val revealed: Boolean = false,
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
    /** IN_PROGRESS = 还没交卷，可以从考试记录里接着考 */
    val status: String = STATUS_FINISHED,
    /** 上次做到第几题（0 基），续考用 */
    val index: Int = 0,
    /** 最后一次改动时间：多设备续考时用它判断谁更新 */
    val updatedAt: Long = 0L,
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
    /** 还没交卷 */
    val inProgress: Boolean get() = status == STATUS_IN_PROGRESS

    val durationText: String
        get() {
            val m = durationSec / 60
            val s = durationSec % 60
            return if (m > 0) "${m}分${s}秒" else "${s}秒"
        }

    /**
     * 云端文件名。
     *
     * 进行中的考试用 `inprogress_<题库id>_<开始时间>.json`：
     * 换一台手机时只要按前缀找，就能定位到「这场考试的续考文件」，
     * 不用把账号下所有成绩都下载一遍。
     */
    fun fileName(): String {
        if (inProgress) {
            return "inprogress_${bankId}_${if (startedAt > 0) startedAt else System.currentTimeMillis()}.json"
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date(if (finishedAt > 0) finishedAt else System.currentTimeMillis()))
        val safeUser = user.safeSegment().take(20).ifBlank { "匿名" }
        val safeBank = bankName.safeSegment().take(40).ifBlank { "题库" }
        return "${stamp}_${safeUser}_${safeBank}.json"
    }


    companion object {
        const val KIND_EXAM = "EXAM"
        const val STATUS_IN_PROGRESS = "IN_PROGRESS"
        const val STATUS_FINISHED = "FINISHED"

        /** 「进行中」文件名的固定前缀（按题库区分）。 */
        fun inProgressPrefix(bankId: Long): String = "inprogress_${bankId}_"

        private fun String.safeSegment(): String = trim().map { ch ->
            when {
                ch.isLetterOrDigit() || ch == '-' || ch == '_' -> ch
                ch.code > 127 -> ch
                else -> '_'
            }
        }.joinToString("").replace(Regex("_+"), "_").trim('_')

        /** 从卷子里取一题，填成记录条目。[results]/[userAnswers] 传空就是「还没考」。 */
        private fun detailOf(
            index: Int,
            item: ExamItem,
            correct: Boolean?,
            my: List<String>,
            revealed: Boolean,
        ) = ExamDetail(
            index = index + 1,
            type = item.question.type.label,
            score = item.score,
            correct = correct,
            my = my.joinToString("|"),
            answer = item.question.answerText(),
            stem = item.question.stem,
            analysis = item.question.analysis,
            options = item.question.options.map { it.text },
            optionKeys = item.question.options.map { it.key },
            answerKeys = item.question.answers,
            revealed = revealed,
        )

        /**
         * 点「考试」的那一刻就建一条**进行中**的记录：
         * 抽好的题 + 每题分值都存进去，这样中途退出还能接着考。
         */
        fun start(paper: ExamPaper, meta: ExamMeta): ExamRecord = ExamRecord(
            status = STATUS_IN_PROGRESS,
            appVersion = meta.appVersion,
            user = meta.user,
            bankId = meta.bankId,
            bankName = meta.bankName,
            bankKey = meta.bankKey,
            category = meta.category,
            questionCount = paper.questionCount,
            totalPoints = paper.totalPoints,
            startedAt = meta.startedAt,
            device = meta.device,
            details = paper.items.mapIndexed { index, item ->
                detailOf(index, item, correct = null, my = emptyList(), revealed = false)
            },
        )

        /** 把一套卷子 + 作答结果整理成**已交卷**的记录。 */
        fun from(
            paper: ExamPaper,
            results: Map<Int, Boolean?>,
            userAnswers: Map<Int, List<String>>,
            meta: ExamMeta,
        ): ExamRecord {
            val summary = ExamBuilder.score(paper, results)
            val details = paper.items.mapIndexed { index, item ->
                detailOf(
                    index = index,
                    item = item,
                    correct = results[index],
                    my = userAnswers[index].orEmpty(),
                    revealed = results[index] != null || !userAnswers[index].isNullOrEmpty(),
                )
            }
            val duration = ((meta.finishedAt - meta.startedAt).coerceAtLeast(0) / 1000).toInt()
            return ExamRecord(
                status = STATUS_FINISHED,
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

    // ------------------------------------------------------------ 续考

    /**
     * 边考边存：更新某一题的作答与判分结果，并记住当前做到第几题。
     * 每次作答后调用一次，退出重进就能接着考。
     */
    fun withAnswer(
        questionIndex: Int,
        my: List<String>,
        correct: Boolean?,
        revealed: Boolean,
        currentIndex: Int,
    ): ExamRecord {
        if (questionIndex !in details.indices) return this
        val list = details.toMutableList()
        list[questionIndex] = list[questionIndex].copy(
            my = my.joinToString("|"),
            correct = correct,
            revealed = revealed,
        )
        return recount(details = list, currentIndex = currentIndex)
    }

    /** 主观题自评后重算。 */
    fun withSelfGrade(questionIndex: Int, correct: Boolean): ExamRecord {
        if (questionIndex !in details.indices) return this
        val list = details.toMutableList()
        list[questionIndex] = list[questionIndex].copy(correct = correct, revealed = true)
        return recount(details = list, currentIndex = index)
    }

    /** 交卷。 */
    fun finish(finishedAt: Long): ExamRecord = copy(
        status = STATUS_FINISHED,
        updatedAt = System.currentTimeMillis(),
        finishedAt = finishedAt,
        durationSec = if (startedAt > 0) ((finishedAt - startedAt).coerceAtLeast(0) / 1000).toInt() else durationSec,
    )

    private fun recount(details: List<ExamDetail>, currentIndex: Int): ExamRecord {
        val answered = details.count { it.my.isNotBlank() }
        val correctCount = details.count { it.correct == true }
        val wrongCount = details.count { it.correct == false }
        val pending = details.count { it.correct == null && it.revealed }
        val scored = details.sumOf { if (it.correct == true) it.score else 0 }
        return copy(
            details = details,
            index = currentIndex,
            updatedAt = System.currentTimeMillis(),
            answered = answered,
            correctCount = correctCount,
            wrongCount = wrongCount,
            selfGraded = pending,
            scoredPoints = scored,
            percent = if (totalPoints <= 0) 0 else (scored * 100 + totalPoints / 2) / totalPoints,
        )
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
