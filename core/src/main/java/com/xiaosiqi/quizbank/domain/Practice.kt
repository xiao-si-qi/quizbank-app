package com.xiaosiqi.quizbank.domain

import com.xiaosiqi.quizbank.importer.TextNorm
import com.xiaosiqi.quizbank.model.PracticeMode
import com.xiaosiqi.quizbank.model.Question
import com.xiaosiqi.quizbank.model.QuestionType
import kotlin.random.Random

/** 判分。返回 null 表示「无法自动判分」，由用户自评（简答题、缺答案的题）。 */
object AnswerJudge {

    fun isCorrect(question: Question, userAnswers: List<String>): Boolean? {
        if (question.answers.isEmpty()) return null
        val user = userAnswers.map { it.trim() }.filter { it.isNotEmpty() }
        if (user.isEmpty()) return false
        return when (question.type) {
            QuestionType.SINGLE, QuestionType.JUDGE ->
                user.size == 1 && question.answers.first().equals(user[0], ignoreCase = true)
            QuestionType.MULTIPLE -> {
                val expected = question.answers.map { it.uppercase() }.toSet()
                val actual = user.map { it.uppercase() }.toSet()
                expected == actual
            }
            QuestionType.BLANK -> compareBlank(question.answers, user)
            QuestionType.ESSAY -> null
        }
    }

    private fun compareBlank(expected: List<String>, actual: List<String>): Boolean {
        if (expected.size == actual.size) {
            return expected.indices.all { TextNorm.loose(expected[it]) == TextNorm.loose(actual[it]) }
        }
        return TextNorm.loose(expected.joinToString("")) == TextNorm.loose(actual.joinToString(""))
    }

    /** 主观题自评。 */
    fun selfGraded(question: Question): Boolean = question.type == QuestionType.ESSAY || question.answers.isEmpty()
}

/** 组卷：把题库内的题目按模式排列成一次练习。 */
object SessionBuilder {

    fun build(
        questions: List<Question>,
        mode: PracticeMode,
        wrongIds: Set<Long> = emptySet(),
        favoriteIds: Set<Long> = emptySet(),
        shuffleQuestions: Boolean = false,
        shuffleOptions: Boolean = false,
        seed: Long = System.currentTimeMillis(),
    ): List<Question> {
        val random = Random(seed)
        val filtered = when (mode) {
            PracticeMode.SEQUENTIAL -> questions
            PracticeMode.RANDOM -> questions
            PracticeMode.WRONG -> questions.filter { it.id in wrongIds }
            PracticeMode.FAVORITE -> questions.filter { it.id in favoriteIds }
        }
        val ordered = if (mode == PracticeMode.SEQUENTIAL && !shuffleQuestions) {
            filtered.sortedBy { it.orderIndex }
        } else {
            filtered.shuffled(random)
        }
        if (!shuffleOptions) return ordered
        return ordered.mapIndexed { index, q ->
            if (q.options.size < 2) q
            else q.copy(options = q.options.shuffled(Random(seed + index)))
        }
    }
}
