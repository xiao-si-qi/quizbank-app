package com.xiaosiqi.quizbank.importer

import com.xiaosiqi.quizbank.excel.SheetTable
import com.xiaosiqi.quizbank.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧版题库 App 的 CSV 格式兼容回归。
 *
 * 旧版表头是 `id,num,questionType,question,answer_A..E,right_answer,examination_scope,reference,important`，
 * 曾经有两处会**静默解析错**：
 *  1. `answer_A` 被「答案」字段用 contains("answer") 抢走，导致正确答案变成「选项A的文本」，而 `right_answer` 被挤掉；
 *  2. `questionType` 的数字编码 1/2/3/4 不认识，题型全部退化成推断。
 * 这个测试把修复锁住。
 */
class LegacyCsvCompatibilityTest {

    private val header = listOf(
        "id", "num", "questionType", "question",
        "answer_A", "answer_B", "answer_C", "answer_D", "answer_E",
        "right_answer", "examination_scope", "reference", "important",
    )

    private fun parse(vararg rows: List<String>) =
        QuestionParser.parseWorkbook(listOf(SheetTable("Sheet1", listOf(header) + rows.toList())))

    @Test
    fun `旧版单选：答案取 right_answer，选项不丢`() {
        val q = parse(
            listOf("1", "1", "1", "当接近区段无列车占用时，进路（    ）。", "无法取消", "可以无延时地直接取消", "需延时才能取消", "可以取消", "", "B", "专业知识", "《信号设备操作手册》", "核心"),
        ).questions.single()

        assertEquals(QuestionType.SINGLE, q.type)
        assertEquals(listOf("B"), q.answers)
        assertEquals(4, q.options.size)
        assertEquals("无法取消", q.options[0].text)
        assertEquals("可以取消", q.options[3].text)
    }

    @Test
    fun `旧版多选：ABC 这种答案能对上`() {
        val q = parse(
            listOf("3", "1", "2", "ZD6转辙机转换的三个过程是(   )。", "解锁过程", "转换过程", "锁闭过程", "调整过程", "", "ABC", "专业知识", "《信号维修规程》", "核心"),
        ).questions.single()

        assertEquals(QuestionType.MULTIPLE, q.type)
        assertEquals(listOf("A", "B", "C"), q.answers)
        assertEquals(4, q.options.size)
    }

    @Test
    fun `旧版判断：0 表示错，会补上对错选项`() {
        val questions = parse(
            listOf("5", "1", "3", "轨道电路测试极性交叉都采用电压法。", "", "", "", "", "", "0", "专业知识", "信号设备检修及故障处理", "核心"),
            listOf("6", "2", "3", "发生电气特性超标应为预警。", "", "", "", "", "", "1", "专业知识", "专业基础知识", "核心"),
        ).questions

        assertEquals(QuestionType.JUDGE, questions[0].type)
        assertEquals(listOf("A", "B"), questions[0].options.map { it.key })
        assertEquals("0 应该判为「错」", listOf("B"), questions[0].answers)
        assertEquals("1 应该判为「对」", listOf("A"), questions[1].answers)
    }

    @Test
    fun `旧版问答：4 映射成简答题`() {
        val q = parse(
            listOf("7", "1", "4", "侧防的主要任务是什么？", "", "", "", "", "", "侧防的主要任务就是通过操作锁闭和监测相邻分支线路上的道岔。", "专业知识", "《信号维修规程》", "一般"),
        ).questions.single()

        assertEquals(QuestionType.ESSAY, q.type)
        assertTrue(q.answers.single().contains("侧防"))
    }

    @Test
    fun `旧版的 examination_scope 与 reference、important 不会丢`() {
        val q = parse(
            listOf("1", "1", "1", "某题", "甲", "乙", "丙", "丁", "", "A", "专业知识", "《信号设备操作手册》", "核心"),
        ).questions.single()

        assertEquals("专业知识", q.chapter)
        assertTrue("参考资料应该进标签: ${q.tags}", q.tags.contains("《信号设备操作手册》"))
        assertTrue("重要级别应该进标签: ${q.tags}", q.tags.contains("核心"))
    }

    @Test
    fun `中文写法的 答案A 与 答案 也不会互相抢列`() {
        val chineseHeader = listOf("题干", "答案A", "答案B", "答案C", "答案D", "答案", "解析")
        val q = QuestionParser.parseWorkbook(
            listOf(
                SheetTable(
                    "Sheet1",
                    listOf(
                        chineseHeader,
                        listOf("中国的首都是？", "北京", "上海", "广州", "深圳", "A", "常识"),
                    ),
                )
            )
        ).questions.single()

        assertEquals(QuestionType.SINGLE, q.type)
        assertEquals(listOf("A"), q.answers)
        assertEquals(4, q.options.size)
        assertEquals("北京", q.options[0].text)
        assertEquals("常识", q.analysis)
    }

    @Test
    fun `题型列写数字 1234 分别对应单选多选判断简答`() {
        assertEquals(QuestionType.SINGLE, QuestionType.fromText("1"))
        assertEquals(QuestionType.MULTIPLE, QuestionType.fromText("2"))
        assertEquals(QuestionType.JUDGE, QuestionType.fromText("3"))
        assertEquals(QuestionType.ESSAY, QuestionType.fromText("4"))
        // 文字写法不受影响
        assertEquals(QuestionType.SINGLE, QuestionType.fromText("单选题"))
        assertEquals(null, QuestionType.fromText("5"))
    }

    @Test
    fun `旧版 CSV 文件走完整链路也能正确解析（含 GBK 编码）`() {
        val csv = buildString {
            appendLine("id,num,questionType,question,answer_A,answer_B,answer_C,answer_D,answer_E,right_answer,examination_scope,reference,important")
            appendLine("1,1,1,当接近区段无列车占用时，进路（    ）。,无法取消,可以无延时地直接取消,需延时才能取消,可以取消,,B,专业知识,《信号设备操作手册》,核心")
            appendLine("3,1,2,ZD6转辙机转换的三个过程是(   )。,解锁过程,转换过程,锁闭过程,调整过程,,ABC,专业知识,《信号维修规程》,核心")
            appendLine("5,1,3,轨道电路测试极性交叉都采用电压法。,,,,,,0,专业知识,信号设备检修及故障处理,核心")
        }
        // 旧版题库不少是 GBK 编码的 CSV，这里顺便验证编码识别
        val gbk = csv.toByteArray(java.nio.charset.Charset.forName("GB18030"))
        val sheets = com.xiaosiqi.quizbank.excel.WorkbookLoader.load(gbk, "SGZTK_ATS.csv")
        val questions = QuestionParser.parseWorkbook(sheets).questions

        assertEquals(3, questions.size)
        assertEquals(QuestionType.SINGLE, questions[0].type)
        assertEquals(listOf("B"), questions[0].answers)
        assertEquals("无法取消", questions[0].options[0].text)
        assertEquals(QuestionType.MULTIPLE, questions[1].type)
        assertEquals(listOf("A", "B", "C"), questions[1].answers)
        assertEquals(QuestionType.JUDGE, questions[2].type)
        assertEquals(listOf("B"), questions[2].answers)
        assertTrue(questions[0].tags.contains("《信号设备操作手册》"))
    }
}
