package com.xiaosiqi.quizbank.importer

import com.xiaosiqi.quizbank.excel.SheetTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BankListParserTest {

    private fun sheet(vararg rows: List<String>) = SheetTable("题库列表", rows.toList())

    @Test
    fun `标准题库列表能解析`() {
        val table = sheet(
            listOf("题库ID", "题库名称", "分类", "描述", "文件地址", "题目数量", "版本", "标签"),
            listOf("1001", "计算机网络", "计算机", "期末复习", "/题库/网络.xlsx", "120", "2024", "期末,专升本"),
            listOf("1002", "消防安全", "资格证", "", "/题库/消防.xlsx", "300", "v2", ""),
        )
        val result = BankListParser.parse(table)
        assertEquals(2, result.items.size)
        val first = result.items[0]
        assertEquals("1001", first.remoteId)
        assertEquals("计算机网络", first.name)
        assertEquals("/题库/网络.xlsx", first.fileUrl)
        assertEquals(120, first.declaredCount)
        assertEquals(listOf("期末", "专升本"), first.tags)
    }

    @Test
    fun `表头别名与列顺序都能容错`() {
        val table = sheet(
            listOf("序号", "链接", "标题"),
            listOf("1", "/题库/甲.xlsx", "甲题库"),
        )
        val result = BankListParser.parse(table)
        assertEquals("甲题库", result.items[0].name)
        assertEquals("/题库/甲.xlsx", result.items[0].fileUrl)
    }

    @Test
    fun `地址列名不认识时按内容识别`() {
        val table = sheet(
            listOf("题库名称", "随便一列", "另一列"),
            listOf("甲题库", "备注文字", "/题库/甲.xlsx"),
            listOf("乙题库", "备注文字", "https://pan.example.com/d/乙.xlsx"),
        )
        val result = BankListParser.parse(table)
        assertEquals("/题库/甲.xlsx", result.items[0].fileUrl)
        assertTrue(result.items[1].fileUrl.startsWith("https://"))
    }

    @Test
    fun `没有名称列时用文件名兜底`() {
        val table = sheet(
            listOf("文件地址"),
            listOf("/题库/计算机网络基础.xlsx"),
        )
        assertEquals("计算机网络基础", BankListParser.parse(table).items[0].name)
    }

    @Test
    fun `重复地址会自动去重`() {
        val table = sheet(
            listOf("题库名称", "文件地址"),
            listOf("甲", "/题库/甲.xlsx"),
            listOf("甲副本", "/题库/甲.xlsx"),
            listOf("乙", "/题库/乙.xlsx"),
        )
        val result = BankListParser.parse(table)
        assertEquals(2, result.items.size)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `不是题库列表时给出明确提示`() {
        val table = sheet(
            listOf("题干", "答案"),
            listOf("某题", "A"),
        )
        val error = runCatching { BankListParser.parse(table) }.exceptionOrNull()
        assertTrue(error is ImportException)
        assertTrue(error!!.message!!.contains("题库名称"))
    }

    @Test
    fun `跳过空地址行`() {
        val table = sheet(
            listOf("题库名称", "文件地址"),
            listOf("甲", "/题库/甲.xlsx"),
            listOf("空行", ""),
            listOf("乙", "  "),
        )
        assertEquals(1, BankListParser.parse(table).items.size)
    }
}
