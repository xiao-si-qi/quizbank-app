package com.xiaosiqi.quizbank.alist

import com.xiaosiqi.quizbank.excel.WorkbookLoader
import com.xiaosiqi.quizbank.importer.BankListParser
import com.xiaosiqi.quizbank.importer.QuestionParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 针对**真实 alist**的联调测试（默认跳过，只有显式传参数时才跑）：
 *
 * ```
 * ./gradlew :core:test --tests "*AlistLiveIntegrationTest*" \
 *   -Dalist.baseUrl=https://alist.xiaosiqi.cn \
 *   -Dalist.student=student1 -Dalist.studentPwd=你的学生密码
 * ```
 *
 * 它走的是 App 真正用的那条链路：AlistClient → 下载 → WorkbookLoader → 解析器，
 * 所以能验证「匿名读公开题库」「学生读内部题库」「学生只能写自己成绩」「改不了题库」这些真实行为。
 */
class AlistLiveIntegrationTest {

    private val baseUrl = System.getProperty("alist.baseUrl").orEmpty()
    private val student = System.getProperty("alist.student").orEmpty()
    private val studentPwd = System.getProperty("alist.studentPwd").orEmpty()

    private val publicListPath = "/题库/公开/题库清单-公开.xlsx"
    private val fullListPath = "/题库/题库清单-全部.xlsx"
    private val internalBankPath = "/题库/内部/消防安全-内部.xlsx"
    private val imagePath = "/题库/公开/images/http-flow.png"

    @Before
    fun requireTarget() {
        assumeTrue("没有配置 alist.baseUrl，跳过真实联调测试", baseUrl.isNotBlank())
    }

    private fun anonymous() = AlistClient(baseUrl, "", "/题库/公开")

    private suspend fun studentClient(): AlistClient {
        val token = AlistClient(baseUrl, "").login(student, studentPwd)
        val account = AlistClient(baseUrl, token).me()
        println("  学生账号 ${account.username} 的根目录 = ${account.basePath}（角色=${account.roleLabel}）")
        return AlistClient(baseUrl, token, account.basePath)
    }

    @Test
    fun `匿名能读到公开题库清单和题库文件`() = runBlocking {
        val client = anonymous()

        val listFile = client.download(FileRef.AlistPath(publicListPath))
        val items = BankListParser.parse(WorkbookLoader.load(listFile.bytes, "公开清单.xlsx").first()).items
        println("  匿名读到 ${items.size} 个公开题库：${items.map { it.name }}")
        assertEquals(2, items.size)

        // 顺着清单里的地址把题库下下来并解析成题目（完整链路）
        val first = items.first { it.name == "计算机基础" }
        val bankFile = client.download(FileRef.AlistPath(first.fileUrl))
        val questions = QuestionParser.parseWorkbook(WorkbookLoader.load(bankFile.bytes, "计算机基础.xlsx")).questions
        println("  「${first.name}」解析出 ${questions.size} 道题，题型：${questions.groupingBy { it.type.label }.eachCount()}")
        assertEquals(12, questions.size)
        assertTrue(questions.any { it.type.label == "多选题" })
        assertTrue(questions.any { it.type.label == "简答题" })

        // 图片也能取到（并且确实是一张 PNG）
        val image = client.download(FileRef.AlistPath(imagePath))
        assertEquals(0x89.toByte(), image.bytes[0])
        assertEquals("PNG".toByteArray().toList(), image.bytes.slice(1..3))
        println("  图片 ${image.bytes.size} 字节，格式校验通过")
    }

    @Test
    fun `匿名读不到内部题库`() = runBlocking {
        val denied = runCatching { anonymous().download(FileRef.AlistPath(internalBankPath)) }
        assertTrue("匿名不该读到内部题库，却成功了", denied.isFailure)
        println("  匿名访问内部题库被拒绝：${denied.exceptionOrNull()?.message?.lineSequence()?.first()}")
    }

    @Test
    fun `学生账号能读到内部题库（验证 base_path 路径换算）`() = runBlocking {
        val client = studentClient()

        val listFile = client.download(FileRef.AlistPath(fullListPath))
        val items = BankListParser.parse(WorkbookLoader.load(listFile.bytes, "全部清单.xlsx").first()).items
        println("  学生读到 ${items.size} 个题库：${items.map { it.name }}")
        assertEquals(3, items.size)

        val internal = client.download(FileRef.AlistPath(internalBankPath))
        val questions = QuestionParser.parseWorkbook(WorkbookLoader.load(internal.bytes, "内部.xlsx")).questions
        println("  内部题库解析出 ${questions.size} 道题")
        assertEquals(10, questions.size)
    }

    @Test
    fun `学生只能写自己的成绩目录，改不了题库`() = runBlocking {
        val client = studentClient()
        val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())

        // 1) 自己的成绩目录：可以写
        val myScore = "/题库/成绩/$student/live-test-$stamp.json"
        client.uploadText(myScore, """{"app":"QuizBank","user":"$student","probe":true}""")
        val back = client.download(FileRef.AlistPath(myScore))
        assertTrue(String(back.bytes).contains("QuizBank"))
        println("  ✅ 能上传并读回自己的成绩：$myScore")

        // 2) 题库文件：不能改（这是最关键的安全项）
        val tamper = runCatching { client.uploadText(publicListPath, "hacked") }
        assertTrue("学生竟然能改写题库清单！", tamper.isFailure)
        println("  ✅ 改写题库清单被拒绝")

        // 3) 别人的成绩目录：不能写
        val other = if (student == "student1") "student2" else "student1"
        val otherScore = runCatching { client.uploadText("/题库/成绩/$other/live-test-$stamp.json", "x") }
        assertTrue("学生竟然能写别人的成绩目录！", otherScore.isFailure)
        println("  ✅ 写 $other 的成绩目录被拒绝")

        // 4) 别人的成绩目录：读不到
        val peek = runCatching { client.list("/题库/成绩/$other") }
        val leaked = peek.getOrNull().orEmpty().map { it.name }
        assertTrue("学生竟然能看到别人的成绩文件：$leaked", leaked.none { it.startsWith("live-test") })
        println("  ✅ 读不到 $other 的成绩文件")
    }

    @Test
    fun `同一份题库列表在匿名与登录下的表现符合预期`() = runBlocking {
        // 公开清单里的 文件地址 用的是 alist 绝对路径，
        // 匿名账号的根目录是 /题库/公开，登录账号的根目录是 /题库 —— 两种都要能跑通
        val anonymousItems = BankListParser.parse(
            WorkbookLoader.load(anonymous().download(FileRef.AlistPath(publicListPath)).bytes, "p.xlsx").first()
        ).items
        val studentItems = BankListParser.parse(
            WorkbookLoader.load(studentClient().download(FileRef.AlistPath(fullListPath)).bytes, "f.xlsx").first()
        ).items
        assertTrue(anonymousItems.all { it.fileUrl.startsWith("/题库/公开/") })
        assertTrue(studentItems.any { it.fileUrl.startsWith("/题库/内部/") })
        println("  公开清单 ${anonymousItems.size} 条、完整清单 ${studentItems.size} 条，路径均为绝对路径且都能被各自账号解析")
    }
}
