package com.xiaosiqi.quizbank.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 版本号比较：更新检查全靠它，错一点就会一直提示升级、或永远不提示。 */
class UpdateCheckerTest {

    @Test
    fun `高版本号会被识别为有新版本`() {
        assertTrue(UpdateChecker.isNewer("1.0.0", "1.1.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "2.0.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun `同版本或更低版本不算新`() {
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.0.9"))
        assertFalse(UpdateChecker.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `按段比数字而不是按字符串`() {
        // 字符串比较会认为 1.0.9 > 1.0.10，这是经典坑
        assertTrue(UpdateChecker.isNewer("1.0.9", "1.0.10"))
        assertFalse(UpdateChecker.isNewer("1.0.10", "1.0.9"))
    }

    @Test
    fun `带 v 前缀和带后缀的版本号都能比`() {
        assertTrue(UpdateChecker.isNewer("1.0.0", "v1.0.1"))
        assertTrue(UpdateChecker.isNewer("v1.0.0", "1.0.1"))
        assertTrue(UpdateChecker.isNewer("1.1.0-debug", "1.1.1"))
    }

    @Test
    fun `缺省段按 0 处理`() {
        assertFalse(UpdateChecker.isNewer("1.1", "1.1.0"))
        assertTrue(UpdateChecker.isNewer("1.1", "1.1.1"))
    }
}
