package com.xiaosiqi.quizbank.importer

import com.xiaosiqi.quizbank.excel.WorkbookLoader
import com.xiaosiqi.quizbank.model.QuestionType
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用**真实 Word 题库转出来的 CSV** 验证 App 的导入链路。
 *
 * 这些 CSV 由 `1.doc / 2016高级信号工复习题.doc / 2017年信号工（车站与区间）高级工题库.doc`
 * 转换而来（OLE2 → 文本 → 题目）。默认跳过，需要时指定目录：
 *
 * ```
 * ./gradlew :core:test --tests "*WordBankImportTest*" -Dwordbank.dir=/tmp/wordbank/out
 * ```
 */
class WordBankImportTest {

    private val dir = System.getProperty("wordbank.dir").orEmpty()

    @Test
    fun `Word 转出来的 CSV 能被导入链路正确解析`() {
        assumeTrue("没有指定 wordbank.dir，跳过", dir.isNotBlank())
        val folder = File(dir)
        assumeTrue("目录不存在：$dir", folder.isDirectory)
        val files = folder.listFiles { f -> f.name.endsWith(".csv") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("目录里没有 CSV", files.isNotEmpty())

        var grandTotal = 0
        files.forEach { file ->
            val sheets = WorkbookLoader.load(file.readBytes(), file.name)
            val parsed = QuestionParser.parseWorkbook(sheets)
            val qs = parsed.questions
            grandTotal += qs.size

            val byType = qs.groupingBy { it.type }.eachCount()
                .entries.sortedByDescending { it.value }
                .joinToString(" ") { "${it.key.label}=${it.value}" }
            val withAnswer = qs.count { it.answers.isNotEmpty() }
            val withOptions = qs.count { it.options.size >= 2 }
            println("=== ${file.name}")
            println("    题数=${qs.size}  有答案=$withAnswer  带选项=$withOptions")
            println("    题型：$byType")
            qs.take(2).forEach { q ->
                println("    例：${q.type.label} | ${q.stem.take(48)} | 答案=${q.answers} | 选项=${q.options.size}")
            }

            assertTrue("${file.name} 应该解析出题目", qs.isNotEmpty())
            assertTrue("${file.name} 绝大多数题目应该有答案", withAnswer > qs.size * 0.8)
            assertTrue("${file.name} 题干不该是空的", qs.all { it.stem.isNotBlank() })
            // 选择题必须能拿到选项
            val choice = qs.filter { it.type == QuestionType.SINGLE || it.type == QuestionType.MULTIPLE }
            assertTrue("${file.name} 选择题应该带选项", choice.isEmpty() || choice.count { it.options.size >= 2 } > choice.size * 0.9)
        }
        println("三个题库合计 $grandTotal 题")
        assertTrue("合计题数应该上千", grandTotal > 1000)
    }
}
