package com.xiaosiqi.quizbank.importer

import com.xiaosiqi.quizbank.excel.WorkbookLoader
import com.xiaosiqi.quizbank.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 端到端校验：用 App 自己生成的模板 → 再用 App 自己的解析器读回来。
 * 同时把模板文件写到 docs/模板 目录，作为交付物给用户。
 */
class TemplateEndToEndTest {

    @Test
    fun `题库模板能被自己解析成题目`() {
        val bytes = TemplateFactory.questionTemplate()
        val sheets = WorkbookLoader.load(bytes, "题库模板.xlsx")
        val result = QuestionParser.parseWorkbook(sheets)

        assertTrue("模板里应该有示例题目", result.count >= 5)
        assertEquals(1, result.questions.count { it.type == QuestionType.SINGLE && it.options.size >= 4 })
        assertEquals(1, result.questions.count { it.type == QuestionType.MULTIPLE })
        assertEquals(1, result.questions.count { it.type == QuestionType.JUDGE })
        assertEquals(1, result.questions.count { it.type == QuestionType.BLANK })
        assertEquals(1, result.questions.count { it.type == QuestionType.ESSAY })

        // 多选题答案
        val multiple = result.questions.first { it.type == QuestionType.MULTIPLE }
        assertEquals(listOf("A", "B", "D"), multiple.answers)
        // 判断题会补上对错选项
        val judge = result.questions.first { it.type == QuestionType.JUDGE }
        assertEquals(listOf("A", "B"), judge.options.map { it.key })
    }

    @Test
    fun `题库列表模板能被自己解析`() {
        val bytes = TemplateFactory.bankListTemplate()
        val sheets = WorkbookLoader.load(bytes, "题库列表模板.xlsx")
        val result = BankListParser.parse(sheets.first { it.name == TemplateFactory.SHEET_LIST })
        assertEquals(3, result.items.size)
        assertEquals("/题库/计算机网络基础.xlsx", result.items[0].fileUrl)
        assertEquals(120, result.items[0].declaredCount)
    }

    @Test
    fun `示例题库能被自己解析`() {
        val result = QuestionParser.parseWorkbook(WorkbookLoader.load(TemplateFactory.sampleBank(), "示例题库.xlsx"))
        assertTrue(result.count >= 10)
        assertTrue(result.questions.all { it.stem.isNotBlank() })
    }

    @Test
    fun `导出给用户的模板文件`() {
        val dir = File("../docs/模板")
        dir.mkdirs()
        File(dir, "题库模板.xlsx").writeBytes(TemplateFactory.questionTemplate())
        File(dir, "题库列表模板.xlsx").writeBytes(TemplateFactory.bankListTemplate())
        File(dir, "示例题库.xlsx").writeBytes(TemplateFactory.sampleBank())
        File(dir, "示例题库列表.xlsx").writeBytes(TemplateFactory.sampleBankList())

        assertTrue(File(dir, "题库模板.xlsx").length() > 1000)
        assertTrue(File(dir, "题库列表模板.xlsx").length() > 1000)
        assertTrue(File(dir, "示例题库.xlsx").length() > 1000)
    }
}
