package com.xiaosiqi.quizbank.data

import com.xiaosiqi.quizbank.model.Bank
import com.xiaosiqi.quizbank.model.BankProgress
import com.xiaosiqi.quizbank.model.BankStats
import com.xiaosiqi.quizbank.model.DayCount
import com.xiaosiqi.quizbank.model.OverallStats
import com.xiaosiqi.quizbank.model.PracticeMode
import com.xiaosiqi.quizbank.model.Question
import com.xiaosiqi.quizbank.exam.ExamRecord

/**
 * 数据访问接口。抽出接口是为了让练习/统计逻辑能在纯 JVM 上测试，
 * 不必依赖 Android 的 SQLite。
 */
interface QuizStore {

    fun banks(): List<Bank>

    fun bank(id: Long): Bank?

    fun findBank(remoteId: String, fileUrl: String): Bank?

    fun upsertBank(bank: Bank): Long

    fun deleteBank(bankId: Long)

    // ------------------------------------------------------------ 题目

    fun questionCount(bankId: Long): Int

    fun questions(bankId: Long): List<Question>

    fun questionsByIds(ids: List<Long>): List<Question>

    fun question(id: Long): Question?

    /** 用新解析出来的题目整体替换该题库的题目（重新导入时使用）。 */
    fun replaceQuestions(bankId: Long, questions: List<Question>)

    // ------------------------------------------------------- 错题与收藏

    fun wrongQuestions(bankId: Long? = null): List<Question>

    fun favoriteQuestions(): List<Question>

    fun favoriteIds(): Set<Long>

    fun isFavorite(questionId: Long): Boolean

    /** 返回操作后是否处于「已收藏」。 */
    fun toggleFavorite(questionId: Long, bankId: Long): Boolean

    fun removeWrong(questionId: Long)

    fun clearWrong(bankId: Long? = null)

    fun wrongIds(bankId: Long? = null): Set<Long>

    // ------------------------------------------------------------ 记录

    fun recordAnswer(
        bankId: Long,
        questionId: Long,
        correct: Boolean?,
        userAnswer: String,
        mode: PracticeMode,
        autoRemoveWrong: Boolean = false,
    )

    fun stats(bankId: Long): BankStats

    fun overallStats(days: Int = 14): OverallStats

    fun dailyCounts(days: Int): List<DayCount>

    // ------------------------------------------------------------ 进度

    fun progress(bankId: Long): BankProgress?

    fun saveProgress(progress: BankProgress)

    fun resetProgress(bankId: Long)

    fun resetAllProgress()

    // ------------------------------------------------------------ 考试记录
    // 注意：只有考试模式会产生记录；普通练习只写错题本与进度。

    /** 保存一次考试成绩，返回本地 id。 */
    fun saveExam(record: ExamRecord): Long

    fun exams(limit: Int = 200): List<ExamRecord>

    fun exam(localId: Long): ExamRecord?

    fun deleteExam(localId: Long)

    fun markExamUploaded(localId: Long, remotePath: String)
}
