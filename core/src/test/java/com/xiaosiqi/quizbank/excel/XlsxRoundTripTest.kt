package com.xiaosiqi.quizbank.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxRoundTripTest {

    @Test
    fun `写出的 xlsx 能被自己读回来`() {
        val rows = listOf(
            listOf<Any?>("题号", "题型", "题干", "答案"),
            listOf<Any?>("1", "单选题", "中国的首都是？", "A"),
            listOf<Any?>("2", "判断题", "1+1=2", "对"),
            listOf<Any?>(3, "填空题", "含有 \"引号\" 和 <标签> 的题干", "甲乙"),
        )
        val bytes = XlsxWriter.write(listOf(XlsxWriter.Sheet("题目", rows, listOf(6, 8, 40, 10))))

        val sheets = XlsxReader.read(bytes)
        assertEquals(1, sheets.size)
        val sheet = sheets.first()
        assertEquals("题目", sheet.name)
        assertEquals("题号", sheet.cell(0, 0))
        assertEquals("中国的首都是？", sheet.cell(1, 2))
        assertEquals("A", sheet.cell(1, 3))
        assertEquals("对", sheet.cell(2, 3))
        // 数字列不该变成 "3.0"
        assertEquals("3", sheet.cell(3, 0))
        // 特殊字符要能原样回来
        assertEquals("含有 \"引号\" 和 <标签> 的题干", sheet.cell(3, 2))
    }

    @Test
    fun `多个工作表与表名都被保留`() {
        val bytes = XlsxWriter.write(
            listOf(
                XlsxWriter.Sheet("单选题", listOf(listOf<Any?>("题干"), listOf<Any?>("a"))),
                XlsxWriter.Sheet("判断题", listOf(listOf<Any?>("题干"), listOf<Any?>("b"))),
            )
        )
        val sheets = XlsxReader.read(bytes)
        assertEquals(listOf("单选题", "判断题"), sheets.map { it.name })
    }

    @Test
    fun `换行与多空格原样保留`() {
        val text = "第一行\n第二行   带空格"
        val bytes = XlsxWriter.write(listOf(XlsxWriter.Sheet("S", listOf(listOf<Any?>(text)))))
        assertEquals(text, XlsxReader.read(bytes).first().cell(0, 0))
    }

    @Test
    fun `共享字符串与内联字符串都能读`() {
        // 模拟 Excel 生成的 sharedStrings 结构
        val sheetXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                <row r="2"><c r="A2" t="inlineStr"><is><t>内联</t></is></c><c r="B2"><v>42</v></c></row>
              </sheetData>
            </worksheet>
        """.trimIndent()
        val sharedXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
              <si><t>题干</t></si>
              <si><r><t>答案</t></r><rPh sb="0" eb="1"><t>忽略我</t></rPh></si>
            </sst>
        """.trimIndent()
        val bytes = buildZip(
            mapOf(
                "xl/workbook.xml" to """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="数据" sheetId="1" r:id="rId1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
                "xl/sharedStrings.xml" to sharedXml,
                "xl/worksheets/sheet1.xml" to sheetXml,
            )
        )
        val sheet = XlsxReader.read(bytes).first()
        assertEquals("数据", sheet.name)
        assertEquals("题干", sheet.cell(0, 0))
        // 富文本要拼接，且不能混入 rPh 注音
        assertEquals("答案", sheet.cell(0, 1))
        assertEquals("内联", sheet.cell(1, 0))
        assertEquals("42", sheet.cell(1, 1))
    }

    @Test
    fun `合并单元格的值会填满整个区域`() {
        val sheetXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>分类</t></is></c><c r="B1" t="inlineStr"><is><t>题库</t></is></c></row>
                <row r="2"><c r="A2" t="inlineStr"><is><t>计算机</t></is></c><c r="B2" t="inlineStr"><is><t>网络</t></is></c></row>
                <row r="3"><c r="B3" t="inlineStr"><is><t>操作系统</t></is></c></row>
              </sheetData>
              <mergeCells count="1"><mergeCell ref="A2:A3"/></mergeCells>
            </worksheet>
        """.trimIndent()
        val bytes = buildZip(
            mapOf(
                "xl/workbook.xml" to """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
                "xl/worksheets/sheet1.xml" to sheetXml,
            )
        )
        val sheet = XlsxReader.read(bytes).first()
        assertEquals("计算机", sheet.cell(2, 0))
        assertEquals("操作系统", sheet.cell(2, 1))
    }

    @Test
    fun `日期格式的单元格会转成可读日期`() {
        val sheetXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData><row r="1"><c r="A1" s="1"><v>45000</v></c></row></sheetData>
            </worksheet>
        """.trimIndent()
        val styles = """<?xml version="1.0"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs></styleSheet>"""
        val bytes = buildZip(
            mapOf(
                "xl/workbook.xml" to """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
                "xl/styles.xml" to styles,
                "xl/worksheets/sheet1.xml" to sheetXml,
            )
        )
        val value = XlsxReader.read(bytes).first().cell(0, 0)
        assertTrue("日期应被格式化，实际=$value", value.startsWith("2023-"))
    }

    private fun buildZip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
