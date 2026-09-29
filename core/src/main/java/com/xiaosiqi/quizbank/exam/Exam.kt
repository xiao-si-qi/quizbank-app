package com.xiaosiqi.quizbank.exam

import com.xiaosiqi.quizbank.model.Question
import com.xiaosiqi.quizbank.model.QuestionType
import kotlin.random.Random

/**
 * 组卷规则。可以在「题库列表 Excel」里按题库配置，也可以全用默认值。
 *
 * 优先级：显式「组卷」列 > 「考试题数」列 > 默认（按分值凑满 100 分）。
 */
@kotlinx.serialization.Serializable
data class ExamBlueprint(
    /** 试卷题数。仅在「考试题数」列有配置时生效（countExplicit = true）。 */
    val questionCount: Int = DEFAULT_QUESTION_COUNT,
    val countExplicit: Boolean = false,
    /** 题型名 → 每题分值（键是 [QuestionType.name]）。 */
    val scores: Map<String, Int> = emptyMap(),
    /** 题型名 → 题数（「组卷」列显式指定时才有）。 */
    val counts: Map<String, Int> = emptyMap(),
    /** 目标满分：没显式配题数时，按这个分数凑卷。 */
    val targetPoints: Int = DEFAULT_TARGET_POINTS,
) {
    fun scoreOf(type: QuestionType): Int =
        (scores[type.name] ?: DEFAULT_SCORES[type] ?: 1).coerceIn(1, 100)

    fun countFor(type: QuestionType): Int? = counts[type.name]

    val hasExplicitCounts: Boolean get() = counts.isNotEmpty()

    /** 显式指定了题数的题型（按题型顺序）。 */
    val explicitTypes: List<QuestionType>
        get() = QuestionType.entries.filter { (counts[it.name] ?: 0) > 0 }

    /** 人类可读的摘要，显示在考试入口和成绩单上。 */
    fun summary(): String {
        val parts = explicitTypes.map { type -> "${type.label}${countFor(type)}×${scoreOf(type)}" }
        if (parts.isNotEmpty()) return parts.joinToString(" ")
        if (countExplicit) return "共${questionCount}题 · 满分$targetPoints"
        val scored = QuestionType.entries.joinToString(" ") { "${it.label}${scoreOf(it)}分" }
        return "$scored · 满分$targetPoints"
    }

    companion object {
        const val DEFAULT_QUESTION_COUNT = 20
        const val DEFAULT_TARGET_POINTS = 100

        /** 默认分值：单选 1、多选 2、判断 1、填空 2、简答 5。 */
        val DEFAULT_SCORES: Map<QuestionType, Int> = mapOf(
            QuestionType.SINGLE to 1,
            QuestionType.MULTIPLE to 2,
            QuestionType.JUDGE to 1,
            QuestionType.BLANK to 2,
            QuestionType.ESSAY to 5,
        )

        /** 「组卷」列里形如 `单选:10*1` 或 `单选*10*1` 的一段。 */
        private val PLAN_PART = Regex(
            "^\\s*(.+?)\\s*[:：]?\\s*(\\d{1,3})\\s*[*×xX]\\s*(\\d{1,3})\\s*$"
        )

        /**
         * 从 Excel 的若干可选列解析组卷规则。
         *
         * @param questionCount 「考试题数」列原文
         * @param scoreCells 各题型分值列的原文
         * @param plan 「组卷」列原文，例如 `单选:10*1;多选:5*4;判断:10*2`
         */
        fun parse(
            questionCount: String? = null,
            scoreCells: Map<QuestionType, String> = emptyMap(),
            plan: String? = null,
        ): ExamBlueprint {
            val scores = LinkedHashMap<String, Int>()
            QuestionType.entries.forEach { type ->
                val raw = scoreCells[type]
                val value = raw?.let { com.xiaosiqi.quizbank.importer.TextNorm.toIntOrNull(it) }
                scores[type.name] = (value ?: DEFAULT_SCORES[type] ?: 1).coerceIn(1, 100)
            }

            val countValue = questionCount?.let { com.xiaosiqi.quizbank.importer.TextNorm.toIntOrNull(it) }
            val countExplicit = countValue != null && countValue > 0

            val explicitCounts = LinkedHashMap<String, Int>()
            plan?.let { text ->
                text.split(';', '；', ',', '，', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { part ->
                        val m = PLAN_PART.find(part) ?: return@forEach
                        val type = QuestionType.fromText(m.groupValues[1]) ?: return@forEach
                        val count = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                        val score = m.groupValues[3].toIntOrNull()?.takeIf { it > 0 }
                            ?: scores[type.name] ?: 1
                        explicitCounts[type.name] = count
                        scores[type.name] = score.coerceIn(1, 100)
                    }
            }

            return ExamBlueprint(
                questionCount = countValue?.takeIf { it > 0 }?.coerceIn(1, 200) ?: DEFAULT_QUESTION_COUNT,
                countExplicit = countExplicit,
                scores = scores,
                counts = explicitCounts,
                targetPoints = DEFAULT_TARGET_POINTS,
            )
        }
    }
}

/** 试卷里的一道题：题目 + 本题分值。 */
data class ExamItem(val question: Question, val score: Int)

/** 抽好的一套试卷。 */
data class ExamPaper(
    val items: List<ExamItem>,
    val targetPoints: Int = ExamBlueprint.DEFAULT_TARGET_POINTS,
) {
    val questionCount: Int get() = items.size

    /** 试卷满分。理想情况下等于 [targetPoints]，题量不足时会小于它。 */
    val totalPoints: Int get() = items.sumOf { it.score }
}

/** 判分结果。 */
data class ExamResult(
    val scoredPoints: Int,
    val totalPoints: Int,
    val correctCount: Int,
    val wrongCount: Int,
    val answeredCount: Int,
    val selfGradedCount: Int,
) {
    /** 百分制得分（总分固定看作 100）。 */
    val percent: Int
        get() = if (totalPoints <= 0) 0 else (scoredPoints * 100 + totalPoints / 2) / totalPoints
}

/**
 * 组卷引擎。
 *
 * 三种模式，行为都是确定的（同一 seed 结果一致）：
 * 1. Excel 给了「组卷」列 → 严格按配置的题数抽；
 * 2. Excel 只给了「考试题数」→ 按各题型在题库里的题量占比分配题数；
 * 3. 都没给 → 按题型轮转抽题，把满分凑到 100 分（这就是「总分一百分」）。
 */
object ExamBuilder {

    fun build(
        questions: List<Question>,
        blueprint: ExamBlueprint = ExamBlueprint(),
        seed: Long = System.currentTimeMillis(),
    ): ExamPaper {
        val random = Random(seed)
        val byType = questions.groupBy { it.type }
            .mapValues { (_, list) -> list.shuffled(random) }

        val picked: List<ExamItem> = when {
            blueprint.hasExplicitCounts -> pickExplicit(byType, blueprint)
            blueprint.countExplicit -> pickByCount(byType, blueprint)
            else -> pickToTargetPoints(byType, blueprint)
        }

        // 按题型顺序排列（单选→多选→判断→填空→简答），看起来像一张正规卷子
        val ordered = picked.sortedBy { it.question.type.ordinal }
        return ExamPaper(ordered, blueprint.targetPoints)
    }

    /** 判分：只统计答对的题的分值。 */
    fun score(paper: ExamPaper, results: Map<Int, Boolean?>): ExamResult {
        var scored = 0
        var correct = 0
        var wrong = 0
        var answered = 0
        var selfGraded = 0
        paper.items.forEachIndexed { index, item ->
            val result = results[index] ?: return@forEachIndexed
            answered++
            when (result) {
                true -> {
                    correct++
                    scored += item.score
                }
                false -> wrong++
                null -> selfGraded++
            }
        }
        return ExamResult(scored, paper.totalPoints, correct, wrong, answered, selfGraded)
    }

    // ------------------------------------------------------------ 抽题策略

    private fun pickExplicit(
        byType: Map<QuestionType, List<Question>>,
        blueprint: ExamBlueprint,
    ): List<ExamItem> {
        val out = ArrayList<ExamItem>()
        blueprint.explicitTypes.forEach { type ->
            val count = blueprint.countFor(type) ?: 0
            val pool = byType[type].orEmpty()
            pool.take(count).forEach { out.add(ExamItem(it, blueprint.scoreOf(type))) }
        }
        // 显式配置的题型都没题时，兜底抽一批，别给用户一张空卷
        if (out.isEmpty()) return pickToTargetPoints(byType, blueprint)
        return out
    }

    private fun pickByCount(
        byType: Map<QuestionType, List<Question>>,
        blueprint: ExamBlueprint,
    ): List<ExamItem> {
        val available = byType.filterValues { it.isNotEmpty() }
        if (available.isEmpty()) return emptyList()
        val totalAvailable = available.values.sumOf { it.size }
        val out = ArrayList<ExamItem>()

        // 按题量占比分配题数，至少给有题的题型留 1 道
        val quota = LinkedHashMap<QuestionType, Int>()
        var assigned = 0
        available.forEach { (type, pool) ->
            val n = ((blueprint.questionCount.toDouble() * pool.size / totalAvailable).toInt()).coerceAtLeast(1)
            quota[type] = minOf(n, pool.size)
            assigned += quota[type]!!
        }
        // 没分满就按题型顺序补
        var cursor = 0
        val types = available.keys.toList()
        while (assigned < blueprint.questionCount) {
            val type = types[cursor % types.size]
            val pool = available[type]!!
            if ((quota[type] ?: 0) < pool.size) {
                quota[type] = (quota[type] ?: 0) + 1
                assigned++
            } else if (types.all { (quota[it] ?: 0) >= available[it]!!.size }) {
                break
            }
            cursor++
            if (cursor > blueprint.questionCount * types.size + types.size) break
        }
        available.forEach { (type, pool) ->
            pool.take(quota[type] ?: 0).forEach { out.add(ExamItem(it, blueprint.scoreOf(type))) }
        }
        return out
    }

    /** 按题型轮转，把满分凑到 targetPoints（默认 100）。 */
    private fun pickToTargetPoints(
        byType: Map<QuestionType, List<Question>>,
        blueprint: ExamBlueprint,
    ): List<ExamItem> {
        val available = byType.filterValues { it.isNotEmpty() }
        if (available.isEmpty()) return emptyList()

        // 分值高的题型先抽，避免最后被 1 分的题占满
        val order = available.keys.sortedWith(
            compareByDescending<QuestionType> { blueprint.scoreOf(it) }.thenBy { it.ordinal },
        )
        val cursor = LinkedHashMap<QuestionType, Int>().apply { order.forEach { put(it, 0) } }
        val out = ArrayList<ExamItem>()
        var total = 0
        var progress = true

        while (progress && total < blueprint.targetPoints) {
            progress = false
            for (type in order) {
                val score = blueprint.scoreOf(type)
                val pool = available[type] ?: continue
                val used = cursor[type] ?: 0
                if (used >= pool.size) continue
                if (total + score > blueprint.targetPoints) continue
                out.add(ExamItem(pool[used], score))
                cursor[type] = used + 1
                total += score
                progress = true
                if (total >= blueprint.targetPoints) break
            }
        }
        // 一道都放不下（分值都太大）时，至少给几道题
        if (out.isEmpty()) {
            order.forEach { type ->
                available[type]?.firstOrNull()?.let { out.add(ExamItem(it, blueprint.scoreOf(type))) }
            }
        }
        return out
    }
}
