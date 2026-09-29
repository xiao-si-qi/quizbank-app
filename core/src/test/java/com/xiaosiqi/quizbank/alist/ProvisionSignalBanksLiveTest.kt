package com.xiaosiqi.quizbank.alist

import com.xiaosiqi.quizbank.excel.WorkbookLoader
import com.xiaosiqi.quizbank.excel.XlsxWriter
import com.xiaosiqi.quizbank.importer.TextNorm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 一次性配置脚本：把 Word 转出来的信号工题库传到 alist 的**登录可见**目录，
 * 并追加到「题库清单-全部.xlsx」（公开清单不动）。
 *
 * ```
 * ./gradlew :core:test --tests "*ProvisionSignalBanksLiveTest*" \
 *   -Dalist.baseUrl=https://alist.xiaosiqi.cn \
 *   -Dalist.admin=xiaosiqi -Dalist.adminPwd=*** \
 *   -Dalist.student=student1 -Dalist.studentPwd=你的学生密码 \
 *   -Dbank.srcDir=/tmp/wordbank/out
 * ```
 */
class ProvisionSignalBanksLiveTest {

    private val baseUrl = System.getProperty("alist.baseUrl").orEmpty()
    private val admin = System.getProperty("alist.admin").orEmpty()
    private val adminPwd = System.getProperty("alist.adminPwd").orEmpty()
    private val student = System.getProperty("alist.student").orEmpty()
    private val studentPwd = System.getProperty("alist.studentPwd").orEmpty()
    private val srcDir = System.getProperty("bank.srcDir").orEmpty()

    private val internalDir = "/题库/内部"
    private val fullListPath = "/题库/题库清单-全部.xlsx"
    private val publicListPath = "/题库/公开/题库清单-公开.xlsx"

    @Before
    fun requireTarget() {
        assumeTrue("没有配置 alist.baseUrl，跳过", baseUrl.isNotBlank())
        assumeTrue("没有配置管理员账号，跳过", admin.isNotBlank() && adminPwd.isNotBlank())
        assumeTrue("没有配置 bank.srcDir，跳过", srcDir.isNotBlank())
    }

    /** 要上传的三个题库：源文件、目标文件名、清单里的那一行。 */
    private data class BankRow(
        val sourceName: String,
        val fileName: String,
        val title: String,
        val description: String,
        val count: Int,
        val version: String,
        val tags: String,
        val plan: String,
    )

    private val banks = listOf(
        BankRow(
            "2017年信号工（车站与区间）高级工题库.csv",
            "信号工-2017车站与区间高级工题库.csv",
            "信号工 · 2017 车站与区间高级工题库",
            "铁路信号工（车站与区间）高级工题库，含判断/选择/填空/简答",
            1393, "word-2017", "信号工,高级工,考证",
            "单选:40*1;判断:20*1;填空:10*2;简答:2*10",
        ),
        BankRow(
            "2016高级信号工复习题.csv",
            "信号工-2016高级工复习题.csv",
            "信号工 · 2016 高级工复习题",
            "2016 高级信号工培训复习题，含判断/选择/填空/简答",
            1433, "word-2016", "信号工,高级工,复习题",
            "单选:40*1;判断:20*1;填空:10*2;简答:2*10",
        ),
        BankRow(
            "1.csv",
            "信号工-高级工题库-第三部分.csv",
            "信号工 · 高级工题库（第三部分）",
            "高级工题库第三部分：填空/选择/判断/简答",
            513, "word-3", "信号工,高级工",
            "单选:40*1;判断:20*1;填空:10*2;简答:2*10",
        ),
    )

    private suspend fun adminClient(): AlistClient {
        val token = AlistClient(baseUrl, "").login(admin, adminPwd)
        val account = AlistClient(baseUrl, token).me()
        assertTrue("配置的账号应该是管理员", account.isAdmin)
        return AlistClient(baseUrl, token, account.basePath)
    }

    @Test
    fun `上传信号工题库到内部目录并追加到完整清单`() = runBlocking {
        val client = adminClient()
        val src = File(srcDir)
        banks.forEach { require(src.resolve(it.sourceName).isFile) { "缺少源文件：${it.sourceName}" } }

        // ---------------- 1. 上传题库文件到 /题库/内部 ----------------
        client.ensureDir(internalDir)
        val uploaded = ArrayList<String>()
        banks.forEach { bank ->
            val bytes = src.resolve(bank.sourceName).readBytes()
            val path = "$internalDir/${bank.fileName}"
            client.upload(path, bytes, "text/csv; charset=utf-8")
            uploaded.add(path)
            println("  已上传 $path（${bytes.size} 字节）")
        }

        // ---------------- 2. 读现有完整清单 ----------------
        val listBytes = client.download(FileRef.AlistPath(fullListPath)).bytes
        val sheet = WorkbookLoader.load(listBytes, "题库清单-全部.xlsx").first()
        val oldHeader = sheet.rows.first()
        val oldRows = sheet.rows.drop(1).filter { r -> r.any { it.isNotBlank() } }
        println("  原清单表头：$oldHeader")
        println("  原清单 ${oldRows.size} 行：" + oldRows.map { it.firstOrNull().orEmpty() }.joinToString())

        // 缺「组卷」列就补上（列位置跟着表头走，不写死）
        val header = if (oldHeader.any { TextNorm.header(it) == "组卷" }) oldHeader
        else oldHeader + listOf("组卷")
        val width = header.size

        fun buildRow(bank: BankRow): List<String> {
            val values = mapOf(
                "题库名称" to bank.title,
                "分类" to "信号工",
                "描述" to bank.description,
                "文件地址" to "$internalDir/${bank.fileName}",
                "题目数量" to bank.count.toString(),
                "版本" to bank.version,
                "标签" to bank.tags,
                "组卷" to bank.plan,
            )
            return header.map { h -> values[TextNorm.header(h)] ?: "" }
        }

        val keptRows = oldRows.map { it + List(maxOf(0, width - it.size)) { "" } }
            .filterNot { row -> banks.any { b -> row.any { it.contains(b.fileName) } } }
        val newRows = keptRows + banks.map(::buildRow)

        // ---------------- 3. 写回完整清单 ----------------
        val out = XlsxWriter.write(
            listOf(XlsxWriter.Sheet(name = sheet.name.ifBlank { "题库列表" }, rows = listOf(header) + newRows)),
        )
        client.upload(fullListPath, out, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        println("  已更新 $fullListPath：${oldRows.size} 行 → ${newRows.size} 行（${out.size} 字节）")

        // ---------------- 4. 复核 ----------------
        val reread = WorkbookLoader.load(client.download(FileRef.AlistPath(fullListPath)).bytes, "x.xlsx").first()
        // 注意：原清单第一列是「题库ID」，所以按表头找「题库名称」那一列，不能写死第 0 列
        val nameCol = reread.rows.first().indexOfFirst { TextNorm.header(it) == "题库名称" }
        assertTrue("找不到题库名称列：${reread.rows.first()}", nameCol >= 0)
        val names = reread.rows.drop(1).map { it.getOrElse(nameCol) { "" } }
        assertEquals("清单行数应该对得上", newRows.size + 1, reread.rows.size)
        banks.forEach { b ->
            assertTrue("清单里应该有「${b.title}」，实际：$names", names.any { it == b.title })
        }
        println("  复核通过，清单现有题库：" + names.filter { it.isNotBlank() }.joinToString("、"))

        // 公开清单不能被改动（匿名用户看不到内部题库）
        val publicSheet = WorkbookLoader.load(client.download(FileRef.AlistPath(publicListPath)).bytes, "y.xlsx").first()
        val publicNameCol = publicSheet.rows.first().indexOfFirst { TextNorm.header(it) == "题库名称" }
        val publicNames = publicSheet.rows.drop(1).map { it.getOrElse(maxOf(publicNameCol, 0)) { "" } }
        banks.forEach { b ->
            assertTrue("公开清单里不该出现「${b.title}」", publicNames.none { it == b.title })
        }
        println("  公开清单仍是：" + publicNames.filter { it.isNotBlank() }.joinToString("、"))

        // 匿名读不到内部题库，登录用户能读到
        val anon = AlistClient(baseUrl, "")
        val anonResult = runCatching { anon.download(FileRef.AlistPath(uploaded.first())) }
        println("  匿名访问内部题库：" + (anonResult.exceptionOrNull()?.message ?: "竟然成功了（不应该）"))
        assertTrue("匿名不该能读内部题库", anonResult.isFailure)

        if (student.isNotBlank() && studentPwd.isNotBlank()) {
            val studentToken = AlistClient(baseUrl, "").login(student, studentPwd)
            val studentAccount = AlistClient(baseUrl, studentToken).me()
            val studentClient = AlistClient(baseUrl, studentToken, studentAccount.basePath)
            val ok = runCatching { studentClient.download(FileRef.AlistPath(uploaded.first())) }
            assertTrue("登录学生应该能读内部题库：${ok.exceptionOrNull()?.message}", ok.isSuccess)
            println("  学生 $student 读内部题库：成功（${ok.getOrNull()?.bytes?.size} 字节）")

            // 端到端：alist 下载 → 解析成题目（这才是 App 真正走的那条链路）
            val bytes = ok.getOrThrow().bytes
            val parsed = com.xiaosiqi.quizbank.importer.QuestionParser
                .parseWorkbook(WorkbookLoader.load(bytes, banks.first().fileName))
            val byType = parsed.questions.groupingBy { it.type.label }.eachCount()
            println("  端到端解析：${parsed.questions.size} 题  $byType")
            assertEquals("解析题数应该和清单里写的一致", banks.first().count, parsed.questions.size)
            assertTrue("应该有选项", parsed.questions.count { it.options.size >= 2 } > 300)
            assertTrue("绝大多数应该有答案", parsed.questions.count { it.answers.isNotEmpty() } > parsed.questions.size * 0.95)
        }
    }
}
