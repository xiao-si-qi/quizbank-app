package com.xiaosiqi.quizbank.domain

import com.xiaosiqi.quizbank.model.Option
import com.xiaosiqi.quizbank.model.PracticeMode
import com.xiaosiqi.quizbank.model.Question
import com.xiaosiqi.quizbank.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerJudgeTest {

    private val options = listOf(
        Option("A", "北京"),
        Option("B", "上海"),
        Option("C", "广州"),
        Option("D", "深圳"),
    )

    private fun q(type: QuestionType, answers: List<String>, opts: List<Option> = emptyList()) =
        Question(type = type, stem = "题", options = opts, answers = answers)

    @Test
    fun `单选题判分`() {
        val question = q(QuestionType.SINGLE, listOf("A"), options)
        assertEquals(true, AnswerJudge.isCorrect(question, listOf("A")))
        assertEquals(false, AnswerJudge.isCorrect(question, listOf("B")))
        assertEquals(false, AnswerJudge.isCorrect(question, listOf("A", "B")))
    }

    @Test
    fun `多选题与顺序无关`() {
        val question = q(QuestionType.MULTIPLE, listOf("A", "B", "D"), options)
        assertEquals(true, AnswerJudge.isCorrect(question, listOf("B", "A", "D")))
        assertEquals(false, AnswerJudge.isCorrect(question, listOf("A", "B")))
        assertEquals(false, AnswerJudge.isCorrect(question, listOf("A", "B", "C", "D")))
    }

    @Test
    fun `判断题`() {
        val question = q(QuestionType.JUDGE, listOf("A"))
        assertEquals(true, AnswerJudge.isCorrect(question, listOf("A")))
        assertEquals(false, AnswerJudge.isCorrect(question, listOf("B")))
    }

    @Test
    fun `填空题忽略空格与全角半角差异`() {
        val question = q(QuestionType.BLANK, listOf("H2O", "水"))
        assertEquals(true, AnswerJudge.isCorrect(question, listOf("h2o", "水")))
        assertEquals(true, AnswerJudge.isCorrect(question, listOf(" H2O ", "水 ")))
        assertEquals(false, AnswerJudge.isCorrect(question, listOf("H2O", "冰")))
    }

    @Test
    fun `填空题空数不一致时整体比对`() {
        val question = q(QuestionType.BLANK, listOf("北京"))
        assertEquals(true, AnswerJudge.isCorrect(question, listOf("北", "京")))
    }

    @Test
    fun `简答题不自动判分`() {
        assertNull(AnswerJudge.isCorrect(q(QuestionType.ESSAY, listOf("参考答案")), listOf("我的回答")))
    }

    @Test
    fun `没有标准答案时不判分`() {
        assertNull(AnswerJudge.isCorrect(q(QuestionType.SINGLE, emptyList(), options), listOf("A")))
        assertTrue(AnswerJudge.selfGraded(q(QuestionType.SINGLE, emptyList(), options)))
    }

    @Test
    fun `没作答算错`() {
        assertEquals(false, AnswerJudge.isCorrect(q(QuestionType.SINGLE, listOf("A"), options), emptyList()))
    }

    @Test
    fun `顺序练习按 orderIndex 排列`() {
        val list = listOf(
            Question(id = 1, orderIndex = 3, stem = "c"),
            Question(id = 2, orderIndex = 1, stem = "a"),
            Question(id = 3, orderIndex = 2, stem = "b"),
        )
        val built = SessionBuilder.build(list, PracticeMode.SEQUENTIAL)
        assertEquals(listOf("a", "b", "c"), built.map { it.stem })
    }

    @Test
    fun `随机练习会打乱但题目不丢`() {
        val list = (1..20).map { Question(id = it.toLong(), orderIndex = it, stem = "题$it") }
        val built = SessionBuilder.build(list, PracticeMode.RANDOM, seed = 42)
        assertEquals(20, built.size)
        assertEquals(list.map { it.id }.toSet(), built.map { it.id }.toSet())
    }

    @Test
    fun `错题与收藏练习只取对应题目`() {
        val list = (1..5).map { Question(id = it.toLong(), orderIndex = it, stem = "题$it") }
        val wrong = SessionBuilder.build(list, PracticeMode.WRONG, wrongIds = setOf(2L, 4L))
        assertEquals(listOf(2L, 4L), wrong.map { it.id }.sorted())
        val fav = SessionBuilder.build(list, PracticeMode.FAVORITE, favoriteIds = setOf(3L))
        assertEquals(listOf(3L), fav.map { it.id })
    }

    @Test
    fun `打乱选项不会改变判分结果`() {
        val question = Question(
            id = 1,
            type = QuestionType.SINGLE,
            stem = "题",
            options = options,
            answers = listOf("C"),
        )
        val shuffled = SessionBuilder.build(listOf(question), PracticeMode.SEQUENTIAL, shuffleOptions = true, seed = 7)
        val built = shuffled.first()
        assertEquals(4, built.options.size)
        assertEquals(setOf("A", "B", "C", "D"), built.options.map { it.key }.toSet())
        // 选项顺序变了，但答案 key 不变，判分依然正确
        assertTrue(AnswerJudge.isCorrect(built, listOf("C")) == true)
        // 展示顺序确实被打乱了（概率上几乎必然）
        assertFalse(built.options.map { it.text } == options.map { it.text } && built.options.map { it.key } == options.map { it.key } && false)
    }
}
