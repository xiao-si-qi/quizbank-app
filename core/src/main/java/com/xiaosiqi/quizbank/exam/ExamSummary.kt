package com.xiaosiqi.quizbank.exam

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 按题库聚合的考试统计。 */
data class ExamBankStat(
    val bankName: String,
    val sessions: Int,
    val questionCount: Int,
    val correctCount: Int,
    val scoredPoints: Int,
    val totalPoints: Int,
) {
    val accuracy: Float
        get() = if (questionCount == 0) 0f else correctCount.toFloat() / questionCount

    /** 平均得分率。 */
    val scoreRate: Float
        get() = if (totalPoints == 0) 0f else scoredPoints.toFloat() / totalPoints
}

/** 按天聚合。 */
data class ExamDayStat(val day: String, val sessions: Int, val scoredPoints: Int, val totalPoints: Int) {
    val scoreRate: Float get() = if (totalPoints == 0) 0f else scoredPoints.toFloat() / totalPoints
}

/** 考试记录的汇总统计（本机或云端通用）。 */
data class ExamSummary(
    val sessions: Int = 0,
    val questionCount: Int = 0,
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    val scoredPoints: Int = 0,
    val totalPoints: Int = 0,
    val durationSec: Int = 0,
    val byBank: List<ExamBankStat> = emptyList(),
    val byDay: List<ExamDayStat> = emptyList(),
    val recent: List<ExamRecord> = emptyList(),
) {
    /** 平均得分率（百分制）。 */
    val percent: Int
        get() = if (totalPoints <= 0) 0 else (scoredPoints * 100 + totalPoints / 2) / totalPoints

    val accuracy: Float
        get() = if (questionCount == 0) 0f else correctCount.toFloat() / questionCount

    /** 及格线（60 分）以上算通过。 */
    val passed: Boolean get() = sessions > 0 && percent >= 60

    val durationText: String
        get() {
            val h = durationSec / 3600
            val m = (durationSec % 3600) / 60
            return when {
                h > 0 -> "${h}小时${m}分"
                m > 0 -> "${m}分钟"
                else -> "${durationSec}秒"
            }
        }

    companion object {
        private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun of(records: List<ExamRecord>, days: Int = 14): ExamSummary {
            if (records.isEmpty()) return ExamSummary()
            val byBank = records.groupBy { it.bankName.ifBlank { "未命名题库" } }
                .map { (name, list) ->
                    ExamBankStat(
                        bankName = name,
                        sessions = list.size,
                        questionCount = list.sumOf { it.questionCount },
                        correctCount = list.sumOf { it.correctCount },
                        scoredPoints = list.sumOf { it.scoredPoints },
                        totalPoints = list.sumOf { it.totalPoints },
                    )
                }
                .sortedByDescending { it.sessions }

            val dayMap = HashMap<String, Triple<Int, Int, Int>>()
            records.forEach { record ->
                val key = dayFmt.format(Date(if (record.finishedAt > 0) record.finishedAt else System.currentTimeMillis()))
                val (sessions, scored, total) = dayMap[key] ?: Triple(0, 0, 0)
                dayMap[key] = Triple(sessions + 1, scored + record.scoredPoints, total + record.totalPoints)
            }
            val byDay = dayMap.entries.sortedBy { it.key }.takeLast(days)
                .map { ExamDayStat(it.key.substringAfter('-'), it.value.first, it.value.second, it.value.third) }

            return ExamSummary(
                sessions = records.size,
                questionCount = records.sumOf { it.questionCount },
                correctCount = records.sumOf { it.correctCount },
                wrongCount = records.sumOf { it.wrongCount },
                scoredPoints = records.sumOf { it.scoredPoints },
                totalPoints = records.sumOf { it.totalPoints },
                durationSec = records.sumOf { it.durationSec },
                byBank = byBank,
                byDay = byDay,
                recent = records.sortedByDescending { it.finishedAt }.take(20),
            )
        }
    }
}
