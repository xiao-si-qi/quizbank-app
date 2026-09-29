package com.xiaosiqi.quizbank

import com.xiaosiqi.quizbank.ui.exam.ExamRecordsViewModel
import com.xiaosiqi.quizbank.ui.exam.ExamRecordsViewModel.RecordTab
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「考试记录」页的标签页要跟着身份走：
 * 匿名只能看本机记录，登录后多出云端记录，管理员才看得到全部用户。
 */
@RunWith(RobolectricTestRunner::class)
class ExamRecordsTabsTest {

    @Test
    fun `未登录只显示本机记录`() {
        val state = ExamRecordsViewModel.UiState()
        assertEquals(listOf(RecordTab.LOCAL), state.availableTabs)
    }

    @Test
    fun `登录后多出云端记录`() {
        val state = ExamRecordsViewModel.UiState(loggedIn = true)
        assertEquals(listOf(RecordTab.LOCAL, RecordTab.CLOUD), state.availableTabs)
    }

    @Test
    fun `管理员才看得到全部用户`() {
        val state = ExamRecordsViewModel.UiState(loggedIn = true, isAdmin = true)
        assertEquals(listOf(RecordTab.LOCAL, RecordTab.CLOUD, RecordTab.ALL_USERS), state.availableTabs)
    }

    @Test
    fun `标签页标题就是中文名`() {
        assertEquals("本机记录", RecordTab.LOCAL.title)
        assertEquals("云端记录", RecordTab.CLOUD.title)
        assertEquals("全部用户", RecordTab.ALL_USERS.title)
    }
}
