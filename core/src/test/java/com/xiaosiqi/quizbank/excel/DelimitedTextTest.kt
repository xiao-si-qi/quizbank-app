package com.xiaosiqi.quizbank.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class DelimitedTextTest {

    @Test
    fun `UTF-8 的 CSV 能正确识别`() {
        val text = "题干,答案\n水的化学式,H2O\n"
        val decoded = DelimitedText.decode(text.toByteArray(Charsets.UTF_8))
        assertEquals("H2O", DelimitedText.parse(decoded.text)[1][1])
    }

    @Test
    fun `GBK 编码的 CSV 不会乱码`() {
        // 国内用户用 Excel 另存的 CSV 大多是 GBK，这是「普通用户不友好」的最大来源
        val text = "题干,答案\n水的化学式是什么,H2O\n中国的首都,北京\n"
        val gbk = text.toByteArray(Charset.forName("GB18030"))
        val decoded = DelimitedText.decode(gbk)
        assertTrue("应该识别为 GB 系列编码，实际=${decoded.charsetName}", decoded.charsetName.contains("GB"))
        val rows = DelimitedText.parse(decoded.text)
        assertEquals("水的化学式是什么", rows[1][0])
        assertEquals("北京", rows[2][1])
    }

    @Test
    fun `带 BOM 的 UTF-8 不会多出乱码字符`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "题干,答案\n甲,乙".toByteArray()
        val decoded = DelimitedText.decode(bytes)
        assertEquals("题干", DelimitedText.parse(decoded.text)[0][0])
    }

    @Test
    fun `自动识别分隔符：逗号 制表符 分号`() {
        assertEquals(',', DelimitedText.detectDelimiter("a,b,c\n1,2,3"))
        assertEquals('\t', DelimitedText.detectDelimiter("a\tb\tc\n1\t2\t3"))
        assertEquals(';', DelimitedText.detectDelimiter("a;b;c\n1;2;3"))
    }

    @Test
    fun `引号内的逗号与换行不会拆错`() {
        val csv = "题干,答案\n\"包含,逗号\",A\n\"包含\n换行\",B\n"
        val rows = DelimitedText.parse(csv)
        assertEquals("包含,逗号", rows[1][0])
        assertEquals("包含\n换行", rows[2][0])
        assertEquals("B", rows[2][1])
    }

    @Test
    fun `双引号转义`() {
        val rows = DelimitedText.parse("题干\n\"他说\"\"你好\"\"\"")
        assertEquals("他说\"你好\"", rows[1][0])
    }

    @Test
    fun `空行会被忽略`() {
        val rows = DelimitedText.parse("a,b\n\n\n1,2\n")
        assertEquals(2, rows.size)
    }

    @Test
    fun `WorkbookLoader 按内容而不是扩展名判断格式`() {
        val csv = "题干,答案\n甲,乙".toByteArray()
        val sheets = WorkbookLoader.load(csv, "随便什么名字.dat")
        assertEquals("乙", sheets.first().cell(1, 1))

        val xlsx = XlsxWriter.write(listOf(XlsxWriter.Sheet("S", listOf(listOf<Any?>("甲")))))
        assertEquals("甲", WorkbookLoader.load(xlsx, "没有扩展名").first().cell(0, 0))
    }

    @Test
    fun `旧版 xls 会给出可操作的中文提示`() {
        val ole2 = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0)
        val error = runCatching { WorkbookLoader.load(ole2, "旧文件.xls") }.exceptionOrNull()
        assertTrue(error is ExcelFormatException)
        assertTrue("提示里应告诉用户怎么办", error!!.message!!.contains("另存为"))
    }
}
