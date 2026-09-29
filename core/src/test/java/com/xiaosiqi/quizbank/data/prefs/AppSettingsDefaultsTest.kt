package com.xiaosiqi.quizbank.data.prefs

import com.xiaosiqi.quizbank.alist.AlistAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 普通用户零配置的关键：默认值 + 跟随登录状态自动切换路径。
 */
class AppSettingsDefaultsTest {

    private fun account(
        id: Long = 5,
        name: String = "student1",
        basePath: String = "/题库",
        roles: List<Int> = listOf(AlistAccount.ROLE_GENERAL),
    ) = AlistAccount(id = id, username = name, basePath = basePath, roles = roles)

    private fun guest() = AlistAccount(
        id = 2,
        username = "guest",
        basePath = "/题库/公开",
        roles = listOf(AlistAccount.ROLE_GUEST),
    )

    @Test
    fun `默认设置开箱即用：有服务器地址、有公开题库清单`() {
        val s = AppSettings()
        assertEquals(AppSettings.DEFAULT_BASE_URL, s.baseUrl)
        assertEquals(AppSettings.DEFAULT_PUBLIC_LIST, s.listPath)
        assertTrue(s.configured)
        assertTrue(s.hasBankListSource)
        assertFalse("默认未登录", s.loggedIn)
        assertTrue("默认自动配置路径", s.autoConfigurePaths)
        assertTrue("默认开启内置题库兜底", s.useBuiltinFallback)
    }

    @Test
    fun `未登录时用公开题库清单且不填成绩目录`() {
        val s = AppSettings(token = "").withAutoDefaults(guest())
        assertEquals(AppSettings.DEFAULT_PUBLIC_LIST, s.listPath)
        assertEquals("", s.scoreUploadPath)
        assertFalse(s.hasScorePath)
    }

    @Test
    fun `登录后自动切到完整清单并把成绩目录指向共用的成绩根目录`() {
        val s = AppSettings(token = "t", username = "student1").withAutoDefaults(account())
        assertEquals(AppSettings.DEFAULT_FULL_LIST, s.listPath)
        // 填的是「根目录」；具体到某个用户的子目录由 ExamRepository 拼（只拼一次）
        assertEquals(AppSettings.DEFAULT_SCORE_DIR, s.scoreUploadPath)
        assertEquals("student1", s.displayName)
        assertTrue(s.hasScorePath)
    }

    @Test
    fun `管理员根目录是斜杠时成绩目录仍是共用的成绩根目录`() {
        // 回归：早期版本会算成 /成绩/xiaosiqi，落到 alist 根目录，管理员看不到别人的成绩
        val s = AppSettings(token = "t").withAutoDefaults(account(name = "xiaosiqi", basePath = "/"))
        assertEquals(AppSettings.DEFAULT_SCORE_DIR, s.scoreUploadPath)
        assertTrue(s.scoreUploadPath.startsWith("/题库/"))
    }

    @Test
    fun `账号根目录带子路径也不会把成绩目录带偏`() {
        val s = AppSettings(token = "t").withAutoDefaults(account(name = "s9", basePath = "/我的题库/"))
        assertEquals(AppSettings.DEFAULT_SCORE_DIR, s.scoreUploadPath)
    }

    @Test
    fun `成绩署名只拼一次用户名`() {
        // 回归：早期版本 settings 里已含用户名，上传时又拼一次 → /成绩/xiaosiqi/xiaosiqi
        val s = AppSettings(token = "t", username = "xiaosiqi").withAutoDefaults(account(name = "xiaosiqi", basePath = "/"))
        val repo = com.xiaosiqi.quizbank.exam.ExamRepository(
            FakeExamStore(), InMemorySettings(s),
        ) { throw IllegalStateException("这个用例不该发请求") }
        assertEquals("/题库/成绩/xiaosiqi", repo.myDir())
        assertEquals("/题库/成绩", repo.scoreRoot())
    }

    @Test
    fun `历史 bug 留下的成绩目录会被修复`() {
        val broken = AppSettings(token = "t", scoreUploadPath = "/成绩/xiaosiqi")
        assertEquals(AppSettings.DEFAULT_SCORE_DIR, broken.repaired().scoreUploadPath)
        val broken2 = AppSettings(token = "t", scoreUploadPath = "/成绩")
        assertEquals(AppSettings.DEFAULT_SCORE_DIR, broken2.repaired().scoreUploadPath)
        // 正常路径和用户自定义路径不动
        val ok = AppSettings(token = "t", scoreUploadPath = AppSettings.DEFAULT_SCORE_DIR)
        assertEquals(AppSettings.DEFAULT_SCORE_DIR, ok.repaired().scoreUploadPath)
        val custom = AppSettings(token = "t", scoreUploadPath = "/我们学校的/成绩")
        assertEquals("/我们学校的/成绩", custom.repaired().scoreUploadPath)
    }

    @Test
    fun `用户自定义成绩根目录时也只在下面加一层用户名`() {
        val s = AppSettings(token = "t", autoConfigurePaths = false, scoreUploadPath = "/我们学校/考试成绩")
        val repo = com.xiaosiqi.quizbank.exam.ExamRepository(
            FakeExamStore(), InMemorySettings(s),
        ) { throw IllegalStateException("这个用例不该发请求") }
        assertEquals("/我们学校/考试成绩/匿名", repo.myDir())
    }

    @Test
    fun `已登录但账号是 guest 时仍按未登录处理`() {
        val s = AppSettings(token = "t").withAutoDefaults(guest())
        assertEquals(AppSettings.DEFAULT_PUBLIC_LIST, s.listPath)
        assertEquals("", s.scoreUploadPath)
    }

    @Test
    fun `用户手动改过路径就不再自动覆盖`() {
        val custom = AppSettings(
            token = "t",
            autoConfigurePaths = false,
            listPath = "/我自己的/清单.xlsx",
            scoreUploadPath = "/我自己的/成绩",
        ).withAutoDefaults(account())
        assertEquals("/我自己的/清单.xlsx", custom.listPath)
        assertEquals("/我自己的/成绩", custom.scoreUploadPath)
    }

    @Test
    fun `自定义署名不会被账号名覆盖`() {
        val s = AppSettings(token = "t", displayName = "张三").withAutoDefaults(account(name = "student1"))
        assertEquals("张三", s.displayName)
    }

    @Test
    fun `服务器地址被清空时会补回默认值`() {
        val s = AppSettings(baseUrl = "").withAutoDefaults(null)
        assertEquals(AppSettings.DEFAULT_BASE_URL, s.baseUrl)
    }

    @Test
    fun `登录流程端到端：匿名到登录的设置变化`() {
        var s = AppSettings()
        // 启动：匿名
        s = s.withAutoDefaults(guest())
        assertFalse(s.hasScorePath)
        assertEquals(AppSettings.DEFAULT_PUBLIC_LIST, s.listPath)

        // 登录成功
        s = s.copy(token = "tok", username = "student2").withAutoDefaults(account(name = "student2"))
        assertEquals(AppSettings.DEFAULT_FULL_LIST, s.listPath)
        assertEquals(AppSettings.DEFAULT_SCORE_DIR, s.scoreUploadPath)

        // 退出登录
        s = s.copy(token = "", username = "", password = "").withAutoDefaults(null)
        assertEquals(AppSettings.DEFAULT_PUBLIC_LIST, s.listPath)
        assertEquals("", s.scoreUploadPath)
    }
}

/** 只实现考试记录相关方法的假 store，够 ExamRepository 算目录用。 */
private class FakeExamStore : com.xiaosiqi.quizbank.data.QuizStore {
    private val records = mutableListOf<com.xiaosiqi.quizbank.exam.ExamRecord>()
    override fun saveExam(record: com.xiaosiqi.quizbank.exam.ExamRecord): Long {
        val id = (records.maxOfOrNull { it.localId } ?: 0L) + 1
        records.add(record.copy(localId = id))
        return id
    }
    override fun exams(limit: Int) = records.take(limit)
    override fun exam(localId: Long) = records.firstOrNull { it.localId == localId }
    override fun deleteExam(localId: Long) { records.removeAll { it.localId == localId } }
    override fun markExamUploaded(localId: Long, remotePath: String) {
        val i = records.indexOfFirst { it.localId == localId }
        if (i >= 0) records[i] = records[i].copy(uploaded = true, remotePath = remotePath)
    }
    override fun banks() = emptyList<com.xiaosiqi.quizbank.model.Bank>()
    override fun bank(id: Long) = null
    override fun findBank(remoteId: String, fileUrl: String) = null
    override fun upsertBank(bank: com.xiaosiqi.quizbank.model.Bank) = 0L
    override fun deleteBank(bankId: Long) = Unit
    override fun questionCount(bankId: Long) = 0
    override fun questions(bankId: Long) = emptyList<com.xiaosiqi.quizbank.model.Question>()
    override fun questionsByIds(ids: List<Long>) = emptyList<com.xiaosiqi.quizbank.model.Question>()
    override fun question(id: Long) = null
    override fun replaceQuestions(bankId: Long, questions: List<com.xiaosiqi.quizbank.model.Question>) = Unit
    override fun wrongQuestions(bankId: Long?) = emptyList<com.xiaosiqi.quizbank.model.Question>()
    override fun favoriteQuestions() = emptyList<com.xiaosiqi.quizbank.model.Question>()
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
