package com.xiaosiqi.quizbank.exam

import com.xiaosiqi.quizbank.model.Option
import com.xiaosiqi.quizbank.model.Question
import com.xiaosiqi.quizbank.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamBuilderTest {

    private fun bank(
        single: Int = 50,
        multiple: Int = 20,
        judge: Int = 30,
        blank: Int = 10,
        essay: Int = 4,
    ): List<Question> {
        val list = ArrayList<Question>()
        var id = 1L
        fun add(type: QuestionType, count: Int, options: Int) {
            repeat(count) { i ->
                list.add(
                    Question(
                        id = id++,
                        orderIndex = list.size,
                        type = type,
                        stem = "${type.label}第${i + 1}题",
                        options = (0 until options).map { Option(('A' + it).toString(), "选项$it") },
                        answers = listOf("A"),
                    )
                )
            }
        }
        add(QuestionType.SINGLE, single, 4)
        add(QuestionType.MULTIPLE, multiple, 4)
        add(QuestionType.JUDGE, judge, 0)
        add(QuestionType.BLANK, blank, 0)
        add(QuestionType.ESSAY, essay, 0)
        return list
    }

    // ------------------------------------------------------------ 默认组卷

    @Test
    fun `默认组卷把满分凑到一百分`() {
        val paper = ExamBuilder.build(bank(), ExamBlueprint(), seed = 1)
        assertEquals("默认满分应该是 100", 100, paper.totalPoints)
        assertTrue("题数应在合理范围：${paper.questionCount}", paper.questionCount in 10..120)
    }

    @Test
    fun `默认组卷会覆盖所有题型`() {
        val paper = ExamBuilder.build(bank(), ExamBlueprint(), seed = 7)
        val types = paper.items.map { it.question.type }.toSet()
        assertEquals(
            "应该五种题型都抽到",
            setOf(
                QuestionType.SINGLE, QuestionType.MULTIPLE, QuestionType.JUDGE,
                QuestionType.BLANK, QuestionType.ESSAY,
            ),
            types,
        )
    }

    @Test
    fun `题目按题型顺序排列`() {
        val paper = ExamBuilder.build(bank(), ExamBlueprint(), seed = 3)
        val orders = paper.items.map { it.question.type.ordinal }
        assertEquals("应当按题型从单选到简答排序", orders.sorted(), orders)
    }

    @Test
    fun `同一个随机种子结果一致`() {
        val a = ExamBuilder.build(bank(), ExamBlueprint(), seed = 42)
        val b = ExamBuilder.build(bank(), ExamBlueprint(), seed = 42)
        assertEquals(a.items.map { it.question.id }, b.items.map { it.question.id })
    }

    @Test
    fun `题库题量不足时满分小于目标`() {
        val paper = ExamBuilder.build(bank(single = 3, multiple = 1, judge = 1, blank = 0, essay = 0), ExamBlueprint())
        assertTrue("题量不足时满分应低于 100，实际 ${paper.totalPoints}", paper.totalPoints < 100)
        assertEquals("3*1 + 1*2 + 1*1 = 6", 6, paper.totalPoints)
    }

    @Test
    fun `空题库返回空试卷`() {
        val paper = ExamBuilder.build(emptyList(), ExamBlueprint())
        assertEquals(0, paper.questionCount)
        assertEquals(0, paper.totalPoints)
    }

    // -------------------------------------------------------- 显式组卷配置

    @Test
    fun `组卷列可以精确指定题数与分值`() {
        // 20*2 + 5*4 + 10*2 + 5*2 + 1*10 = 100 分，共 41 题
        val blueprint = ExamBlueprint.parse(plan = "单选:20*2;多选:5*4;判断:10*2;填空:5*2;简答:1*10")
        assertEquals(20, blueprint.countFor(QuestionType.SINGLE))
        assertEquals(4, blueprint.scoreOf(QuestionType.MULTIPLE))
        assertEquals(10, blueprint.scoreOf(QuestionType.ESSAY))

        val paper = ExamBuilder.build(bank(), blueprint, seed = 5)
        assertEquals("20+5+10+5+1 = 41 题", 41, paper.questionCount)
        assertEquals("20*2+5*4+10*2+5*2+1*10 = 100 分", 100, paper.totalPoints)
        assertEquals(20, paper.items.count { it.question.type == QuestionType.SINGLE })
        assertEquals(1, paper.items.count { it.question.type == QuestionType.ESSAY })
        assertEquals("简答题每题 10 分", 10, paper.items.first { it.question.type == QuestionType.ESSAY }.score)
    }

    @Test
    fun `组卷列支持 题型星号数量星号分值 的写法`() {
        val blueprint = ExamBlueprint.parse(plan = "单选*20*2;多选*5*4")
        assertEquals(20, blueprint.countFor(QuestionType.SINGLE))
        assertEquals(2, blueprint.scoreOf(QuestionType.SINGLE))
        val paper = ExamBuilder.build(bank(), blueprint, seed = 1)
        assertEquals(20 * 2 + 5 * 4, paper.totalPoints)
    }

    @Test
    fun `显式配置超出题库题量时按实际题量抽`() {
        val blueprint = ExamBlueprint.parse(plan = "单选:999*1")
        val paper = ExamBuilder.build(bank(single = 7), blueprint, seed = 1)
        assertEquals(7, paper.questionCount)
    }

    @Test
    fun `考试题数列按题量占比分配`() {
        val blueprint = ExamBlueprint.parse(questionCount = "20")
        assertTrue(blueprint.countExplicit)
        val paper = ExamBuilder.build(bank(), blueprint, seed = 1)
        assertEquals(20, paper.questionCount)
        // 单选 50/114、多选 20/114…，至少每种有题的题型都能分到
        assertTrue(paper.items.any { it.question.type == QuestionType.ESSAY })
    }

    @Test
    fun `分值列可以只配部分题型`() {
        val blueprint = ExamBlueprint.parse(
            scoreCells = mapOf(
                QuestionType.SINGLE to "2",
                QuestionType.ESSAY to "10",
            ),
        )
        assertEquals(2, blueprint.scoreOf(QuestionType.SINGLE))
        assertEquals(10, blueprint.scoreOf(QuestionType.ESSAY))
        // 没配的用默认值
        assertEquals(2, blueprint.scoreOf(QuestionType.MULTIPLE))
        assertEquals(1, blueprint.scoreOf(QuestionType.JUDGE))
    }

    @Test
    fun `分值列的非法值会被兜住`() {
        val blueprint = ExamBlueprint.parse(
            questionCount = "abc",
            scoreCells = mapOf(QuestionType.SINGLE to "0", QuestionType.MULTIPLE to "-3"),
        )
        assertEquals("非法题数回落默认", ExamBlueprint.DEFAULT_QUESTION_COUNT, blueprint.questionCount)
        assertTrue(blueprint.scoreOf(QuestionType.SINGLE) >= 1)
        assertTrue(blueprint.scoreOf(QuestionType.MULTIPLE) >= 1)
    }

    // ---------------------------------------------------------------- 判分

    @Test
    fun `判分按每题分值累加并给出百分制`() {
        // 单选题 10 道每题 2 分 + 多选题 5 道每题 4 分 = 15 题、满分 40
        val blueprint = ExamBlueprint.parse(plan = "单选:10*2;多选:5*4")
        val paper = ExamBuilder.build(bank(), blueprint, seed = 9)
        assertEquals(15, paper.questionCount)
        assertEquals(40, paper.totalPoints)

        // 前 10 题（单选）全对，多选全错
        val results = paper.items.indices.associateWith { index -> index < 10 }
        val result = ExamBuilder.score(paper, results)
        assertEquals("10 道单选 × 2 分 = 20 分", 20, result.scoredPoints)
        assertEquals(40, result.totalPoints)
        assertEquals(10, result.correctCount)
        assertEquals(5, result.wrongCount)
        assertEquals("20/40 = 50 分", 50, result.percent)
    }

    @Test
    fun `未作答不计入正确或错误`() {
        val paper = ExamBuilder.build(bank(), ExamBlueprint.parse(plan = "单选:5*2"), seed = 1)
        val results = mapOf<Int, Boolean?>(0 to true, 1 to false) // 其余未作答
        val result = ExamBuilder.score(paper, results)
        assertEquals(2, result.answeredCount)
        assertEquals(1, result.correctCount)
        assertEquals(1, result.wrongCount)
        assertEquals(2, result.scoredPoints)
        assertEquals(20, result.percent)
    }

    @Test
    fun `全对是一百分`() {
        val paper = ExamBuilder.build(bank(), ExamBlueprint(), seed = 11)
        val result = ExamBuilder.score(paper, paper.items.indices.associateWith { true })
        assertEquals(100, result.percent)
        assertEquals(paper.totalPoints, result.scoredPoints)
    }

    // ------------------------------------------------------------ 考试记录

    @Test
    fun `考试记录带逐题快照，题库变了也能回顾`() {
        val blueprint = ExamBlueprint.parse(plan = "单选:2*50")
        val paper = ExamBuilder.build(bank(), blueprint, seed = 2)
        val results = mapOf(0 to true, 1 to false)
        val answers = mapOf(0 to listOf("A"), 1 to listOf("B"))

        val record = ExamRecord.from(
            paper = paper,
            results = results,
            userAnswers = answers,
            meta = ExamMeta(
                user = "张三", bankName = "计算机基础", bankKey = "pub-cs", category = "计算机",
                startedAt = 1_000_000, finishedAt = 1_060_000, device = "Pixel",
            ),
        )

        assertEquals(ExamRecord.KIND_EXAM, record.kind)
        assertEquals("张三", record.user)
        assertEquals(2, record.questionCount)
        assertEquals(100, record.totalPoints)
        assertEquals(50, record.scoredPoints)
        assertEquals(50, record.percent)
        assertEquals(60, record.durationSec)
        assertEquals(2, record.details.size)

        val first = record.details[0]
        assertEquals(1, first.index)
        assertEquals("单选题", first.type)
        assertEquals(50, first.score)
        assertEquals(true, first.correct)
        assertEquals("A", first.my)
        assertTrue(first.stem.isNotBlank())
        assertEquals(4, first.options.size)
        assertEquals("A. 选项0", first.options[0])
        assertEquals(false, record.details[1].correct)
    }

    @Test
    fun `考试记录能 JSON 往返且文件名可读`() {
        val paper = ExamBuilder.build(bank(), ExamBlueprint.parse(plan = "单选:1*100"), seed = 1)
        val record = ExamRecord.from(paper, mapOf(0 to true), mapOf(0 to listOf("A")),
            ExamMeta(user = "李四", bankName = "消防安全", finishedAt = 1_700_000_000_000))
        val text = ExamJson.encode(record)
        val back = ExamJson.decode(text)
        assertEquals(record.percent, back.percent)
        assertEquals(record.details.size, back.details.size)
        assertEquals(record.details[0].stem, back.details[0].stem)
        val name = record.fileName()
        assertTrue("文件名应含用户与题库：$name", name.contains("李四") && name.contains("消防安全"))
        assertTrue(name.endsWith(".json"))
    }
}
