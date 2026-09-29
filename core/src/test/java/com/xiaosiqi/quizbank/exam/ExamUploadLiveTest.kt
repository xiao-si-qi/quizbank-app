package com.xiaosiqi.quizbank.exam

import com.xiaosiqi.quizbank.alist.AlistClient
import com.xiaosiqi.quizbank.alist.FileRef
import com.xiaosiqi.quizbank.data.QuizStore
import com.xiaosiqi.quizbank.data.prefs.AppSettings
import com.xiaosiqi.quizbank.data.prefs.InMemorySettings
import com.xiaosiqi.quizbank.model.Question
import com.xiaosiqi.quizbank.model.QuestionType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 考试记录的**真实 alist** 联调（默认跳过，传参才跑）：
 *
 * ```
 * ./gradlew :core:test --tests "*ExamUploadLiveTest*" \
 *   -Dalist.baseUrl=https://alist.xiaosiqi.cn \
 *   -Dalist.student=student1 -Dalist.studentPwd=你的学生密码 \
 *   -Dalist.admin=xiaosiqi -Dalist.adminPwd=***
 * ```
 *
 * 验证三件事：
 * 1. 学生能把自己的考试记录（含逐题快照）传到自己的成绩目录；
 * 2. 传上去的 JSON 能被原样读回来（逐题内容不丢）；
 * 3. 管理员能把**所有人**的考试记录列出来（这就是「管理员可以看到所有人的考试记录」）。
 */
class ExamUploadLiveTest {

    private val baseUrl = System.getProperty("alist.baseUrl").orEmpty()
    private val student = System.getProperty("alist.student").orEmpty()
    private val studentPwd = System.getProperty("alist.studentPwd").orEmpty()
    private val admin = System.getProperty("alist.admin").orEmpty()
    private val adminPwd = System.getProperty("alist.adminPwd").orEmpty()

    @Before
    fun requireTarget() {
        assumeTrue("没有配置 alist.baseUrl，跳过真实联调测试", baseUrl.isNotBlank())
        assumeTrue("没有配置 alist.student，跳过", student.isNotBlank() && studentPwd.isNotBlank())
    }

    private class FakeStore(private val records: MutableList<ExamRecord> = mutableListOf()) : QuizStore {
        override fun saveExam(record: ExamRecord): Long {
            val id = (records.maxOfOrNull { it.localId } ?: 0L) + 1
            records.add(record.copy(localId = id))
            return id
        }

        override fun exams(limit: Int) = records.sortedByDescending { it.finishedAt }.take(limit)
        override fun exam(localId: Long) = records.firstOrNull { it.localId == localId }
    override fun inProgressExam(bankId: Long) =
        records.firstOrNull { it.bankId == bankId && it.inProgress }
        override fun deleteExam(localId: Long) {
            records.removeAll { it.localId == localId }
        }

        override fun markExamUploaded(localId: Long, remotePath: String) {
            val i = records.indexOfFirst { it.localId == localId }
            if (i >= 0) records[i] = records[i].copy(uploaded = true, remotePath = remotePath)
        }

        // ---- 下面是联调不需要的部分 ----
        override fun banks() = emptyList<com.xiaosiqi.quizbank.model.Bank>()
        override fun bank(id: Long) = null
        override fun findBank(remoteId: String, fileUrl: String) = null
        override fun upsertBank(bank: com.xiaosiqi.quizbank.model.Bank) = 0L
        override fun deleteBank(bankId: Long) = Unit
        override fun questionCount(bankId: Long) = 0
        override fun questions(bankId: Long) = emptyList<Question>()
        override fun questionsByIds(ids: List<Long>) = emptyList<Question>()
        override fun question(id: Long) = null
        override fun replaceQuestions(bankId: Long, questions: List<Question>) = Unit
        override fun wrongQuestions(bankId: Long?) = emptyList<Question>()
        override fun favoriteQuestions() = emptyList<Question>()
        override fun favoriteIds() = emptySet<Long>()
        override fun isFavorite(questionId: Long) = false
        override fun toggleFavorite(questionId: Long, bankId: Long) = false
        override fun removeWrong(questionId: Long) = Unit
        override fun clearWrong(bankId: Long?) = Unit
        override fun wrongIds(bankId: Long?) = emptySet<Long>()
        override fun recordAnswer(
            bankId: Long, questionId: Long, correct: Boolean?, userAnswer: String,
            mode: com.xiaosiqi.quizbank.model.PracticeMode, autoRemoveWrong: Boolean,
        ) = Unit
        override fun stats(bankId: Long) = com.xiaosiqi.quizbank.model.BankStats(bankId)
        override fun overallStats(days: Int) = com.xiaosiqi.quizbank.model.OverallStats()
        override fun dailyCounts(days: Int) = emptyList<com.xiaosiqi.quizbank.model.DayCount>()
        override fun progress(bankId: Long) = null
        override fun saveProgress(progress: com.xiaosiqi.quizbank.model.BankProgress) = Unit
        override fun resetProgress(bankId: Long) = Unit
        override fun resetAllProgress() = Unit
    }

    private fun paper(): ExamPaper {
        val questions = listOf(
            Question(
                id = 1, orderIndex = 0, type = QuestionType.SINGLE,
                stem = "联调题目：HTTP 默认端口是？",
                options = listOf(
                    com.xiaosiqi.quizbank.model.Option("A", "21"),
                    com.xiaosiqi.quizbank.model.Option("B", "80"),
                ),
                answers = listOf("B"), analysis = "HTTP 默认 80。",
            ),
            Question(
                id = 2, orderIndex = 1, type = QuestionType.JUDGE,
                stem = "联调题目：地球是圆的。", options = emptyList(), answers = listOf("A"),
            ),
        )
        return ExamPaper(questions.map { ExamItem(it, 50) }, 100)
    }

    private suspend fun clientFor(user: String, pwd: String): AlistClient {
        val token = AlistClient(baseUrl, "").login(user, pwd)
        val account = AlistClient(baseUrl, token).me()
        return AlistClient(baseUrl, token, account.basePath)
    }

    @Test
    fun `学生上传考试记录后管理员能列出来（含逐题快照）`() = runBlocking {
        val settings = InMemorySettings(
            AppSettings(
                baseUrl = baseUrl,
                scoreUploadPath = "/题库/成绩",
                displayName = student,
                autoConfigurePaths = false,
            )
        )
        val store = FakeStore()
        val studentClient = clientFor(student, studentPwd)
        val studentRepo = ExamRepository(store, settings) { studentClient }

        val stamp = System.currentTimeMillis()
        val paper = paper()
        val record = ExamRecord.from(
            paper = paper,
            results = mapOf(0 to true, 1 to false),
            userAnswers = mapOf(0 to listOf("B"), 1 to listOf("A")),
            meta = ExamMeta(
                user = student, bankName = "联调考试题库", bankKey = "live-exam",
                startedAt = stamp - 60_000, finishedAt = stamp, device = "live-test",
            ),
        )
        val localId = store.saveExam(record)
        assertEquals(50, record.percent)
        assertEquals(2, record.details.size)

        // 1) 学生上传
        val path = studentRepo.upload(store.exam(localId)!!)
        println("  已上传：$path")
        assertTrue("上传路径应在自己的成绩目录里：$path", path.contains(student))

        // 2) 原样读回来（逐题不丢）
        val raw = studentClient.download(FileRef.AlistPath(path))
        val back = ExamJson.decode(String(raw.bytes, Charsets.UTF_8))
        assertEquals("得分率应该保持", record.percent, back.percent)
        assertEquals("逐题数量应该保持", record.details.size, back.details.size)
        assertEquals(record.details[0].stem, back.details[0].stem)
        assertEquals("B", back.details[0].my)
        assertEquals("B", back.details[0].answer)
        assertEquals("HTTP 默认 80。", back.details[0].analysis)
        println("  读回：${back.user} ${back.scoredPoints}/${back.totalPoints} = ${back.percent} 分，${back.details.size} 题")

        // 3) 管理员列出所有人的考试记录
        if (admin.isBlank() || adminPwd.isBlank()) {
            println("  没有配置管理员账号，跳过「管理员总览」校验")
            return@runBlocking
        }
        val adminClient = clientFor(admin, adminPwd)
        val adminAccount = adminClient.me()
        println("  管理员 ${adminAccount.username} 角色=${adminAccount.roleLabel} 根目录=${adminAccount.basePath}")
        assertTrue("配置的账号应该是管理员", adminAccount.isAdmin)

        val adminSettings = InMemorySettings(
            AppSettings(
                baseUrl = baseUrl, scoreUploadPath = "/题库/成绩",
                displayName = student, autoConfigurePaths = false,
            )
        )
        val adminRepo = ExamRepository(FakeStore(), adminSettings) { adminClient }
        val all = adminRepo.fetchAllUsers()
        println("  管理员看到 ${all.size} 个用户：" + all.joinToString { "${it.userName}(${it.records.size})" })

        val mine = all.firstOrNull { it.userName == student }
        assertTrue("管理员应该能看到 $student 的考试记录目录", mine != null)
        assertTrue("应该至少有一条记录", (mine?.records?.size ?: 0) > 0)
        val latest = mine!!.records.first()
        assertTrue("管理员应该能回顾到逐题内容", latest.details.isNotEmpty())
        println("  ${student} 最近一次：${latest.bankName} ${latest.percent} 分，${latest.details.size} 题")
    }

    @Test
    fun `普通用户读不到别人的成绩目录`() = runBlocking {
        val settings = InMemorySettings(
            AppSettings(
                baseUrl = baseUrl, scoreUploadPath = "/题库/成绩",
                displayName = student, autoConfigurePaths = false,
            )
        )
        val repo = ExamRepository(FakeStore(), settings) { clientFor(student, studentPwd) }
        val others = runCatching { repo.fetchAllUsers() }
        val sawOthers = others.getOrNull()?.any { it.userName != student } == true
        println("  普通用户尝试读全部用户：${if (others.isFailure) "被拒绝：" + others.exceptionOrNull()?.message else "读到 ${others.getOrNull()?.size} 个用户"}")
        assertTrue("普通用户不应该读到别人的成绩目录", others.isFailure || !sawOthers)
    }

    @Test
    fun `管理员上传也落在共用的成绩目录下而不是根目录`() = runBlocking {
        assumeTrue("没有配置管理员账号，跳过", admin.isNotBlank() && adminPwd.isNotBlank())
        val adminClient = clientFor(admin, adminPwd)

        // 模拟 App 修好之后的设置：成绩目录是共用的根目录，用户名由仓库自己拼
        val settings = InMemorySettings(
            AppSettings(
                baseUrl = baseUrl,
                scoreUploadPath = AppSettings.DEFAULT_SCORE_DIR,
                displayName = admin,
                autoConfigurePaths = false,
            )
        )
        val store = FakeStore()
        val repo = ExamRepository(store, settings) { adminClient }
        assertEquals("/题库/成绩", repo.scoreRoot())
        assertEquals("/题库/成绩/$admin", repo.myDir())

        val now = System.currentTimeMillis()
        val record = ExamRecord.from(
            paper = paper(), results = mapOf(0 to true, 1 to true),
            userAnswers = mapOf(0 to listOf("B"), 1 to listOf("A")),
            meta = ExamMeta(user = admin, bankName = "管理员目录回归", startedAt = now - 1000, finishedAt = now),
        )
        val id = store.saveExam(record)
        val path = repo.upload(store.exam(id)!!)
        println("  管理员上传路径：$path")
        assertTrue("必须落在 /题库/成绩/$admin/ 下，实际 $path", path.startsWith("/题库/成绩/$admin/"))
        assertTrue("不能落在 alist 根目录的 /成绩 下", !path.startsWith("/成绩/"))

        // 管理员视角应该能把自己也列进「全部用户」
        val all = repo.fetchAllUsers()
        val mine = all.firstOrNull { it.userName == admin }
        assertTrue("管理员应该能在总览里看到自己", mine != null)
        println("  管理员总览看到：" + all.joinToString { "${it.userName}(${it.records.size})" })

        adminClient.remove(path)
        println("  已清理测试文件：$path")
    }
}
