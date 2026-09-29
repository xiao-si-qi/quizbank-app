package com.xiaosiqi.quizbank

import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaosiqi.quizbank.data.prefs.AppSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 连**真实 alist** 跑一遍首次启动流程，并把界面截图存到 docs/截图/。
 *
 * 这既是「App 确实能跑」的证据，也是端到端验证：
 * 匿名 → 拉公开题库清单 → 下载题库（含图片）→ 首页出现卡片 → 进练习 → 答对判分。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnlineScreenshotTest {

    /** 用真实的公开题库，不做兜底，这样截图里就是线上真实数据。 */
    private val prepareSettings = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                // 清掉上一个测试留下的数据，确保「首次启动自动加载」这条路径每次都真实走一遍
                File(context.applicationInfo.dataDir, "databases").deleteRecursively()
                File(context.filesDir, "images").deleteRecursively()
                // 用 App 自己的设置实例写入，否则 StateFlow 里还是旧值
                (context.applicationContext as QuizApp).container.settings.edit {
                    it.copy(
                        baseUrl = AppSettings.DEFAULT_BASE_URL,
                        listPath = AppSettings.DEFAULT_PUBLIC_LIST,
                        autoConfigurePaths = false,
                        useBuiltinFallback = true,
                        downloadImagesWithBank = true,
                    )
                }
                base.evaluate()
            }
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(prepareSettings).around(composeRule)

    private val outDir = File("../docs/截图")

    /**
     * 点顶栏右上角的「更多」。
     * 首页每个题库卡片上也有一个「更多」，只按 contentDescription 取第 0 个会点到卡片上，
     * 所以这里按纵坐标挑出顶栏那一个。
     */
    private fun clickTopBarMenu() = clickMore(topBar = true)

    /** 点第一个题库卡片上的「更多」。 */
    private fun clickCardMenu() = clickMore(topBar = false)

    private fun clickMore(topBar: Boolean) {
        val nodes = composeRule.onAllNodesWithContentDescription("更多").fetchSemanticsNodes()
        val index = if (topBar) nodes.indexOfFirst { it.boundsInRoot.top < 250f }
        else nodes.indexOfFirst { it.boundsInRoot.top >= 250f }
        assertTrue("找不到${if (topBar) "顶栏" else "卡片"}的「更多」按钮", index >= 0)
        composeRule.onAllNodesWithContentDescription("更多")[index].performClick()
    }

    private fun shoot(name: String) {
        outDir.mkdirs()
        composeRule.waitForIdle()
        val view = composeRule.activity.window.decorView
        var width = view.width
        var height = view.height
        if (width <= 0 || height <= 0) {
            width = 1233
            height = 2673
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
        }
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        FileOutputStream(File(outDir, name)).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        assertTrue("截图 $name 太小", File(outDir, name).length() > 1000)
    }

    @Test
    fun `首次启动自动加载线上题库并完成一次答题`() {
        // 1. 等首页自动把公开题库下下来
        composeRule.waitUntil(timeoutMillis = 120_000) {
            composeRule.onAllNodesWithText("计算机基础").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        shoot("01-首次启动自动加载线上题库.png")

        // 2. 登录入口
        composeRule.onNodeWithText("去登录").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("用户名").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        shoot("02-登录页只有三个字段.png")

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("计算机基础").fetchSemanticsNodes().isNotEmpty()
        }

        // 3. 进练习页。题库按「最近导入」排序，第一张卡片可能是生活常识，
        //    所以两种第一题都接受，避免测试依赖下载顺序。
        val lifeQuestion = "一年中白昼最长的节气是？"
        val computerQuestion = "HTTP 协议默认使用的端口是？"
        fun has(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        fun hasPart(text: String) =
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

        composeRule.onAllNodesWithText("顺序练习")[0].performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) { has(lifeQuestion) || has(computerQuestion) }
        val usingLifeBank = has(lifeQuestion)
        composeRule.waitForIdle()
        shoot("03-练习页.png")

        // 3.1 左右滑动切题：右下角「题目清单」上的编号应该跟着变
        // 右下角的题目清单按钮（图标带 contentDescription，文字不进语义树）
        assertTrue(
            "练习页应该有右下角的题目清单按钮",
            composeRule.onAllNodesWithContentDescription("题目清单").fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue("一开始应该是第 1 题", hasPart("第 1/"))

        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitUntil(timeoutMillis = 10_000) { hasPart("第 2/") }
        println("  左滑切题：第 1 题 → 第 2 题 ✓")

        composeRule.onRoot().performTouchInput { swipeRight() }
        composeRule.waitUntil(timeoutMillis = 10_000) { hasPart("第 1/") }
        println("  右滑回退：第 2 题 → 第 1 题 ✓")

        // 4. 答对并看解析
        composeRule.onAllNodesWithText(if (usingLifeBank) "夏至" else "80")[0].performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("回答正确").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        shoot("04-答对与解析.png")

        // 5. 回首页，从题库卡片菜单里「开始考试」
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("计算机基础").fetchSemanticsNodes().isNotEmpty()
        }
        // 「考试」现在和顺序练习/随机练习同级，直接从卡片上点
        assertTrue("卡片上应该有同级的考试按钮", has("考试"))
        composeRule.onAllNodesWithText("考试")[0].performClick()

        // 组卷提示弹窗
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("本次试卷共", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // 注意：Compose 的对话框在独立窗口里，decorView.draw() 截不到，
        // 所以「组卷提示」「答题卡」这类弹窗不截图。
        composeRule.onNodeWithText("开始答题").performClick()

        // 6. 考试中：答题不显示答案
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("第 1/", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        shoot("05-考试模式答题中.png")

        // 考试页同样能左右滑动切题
        assertTrue("考试页第 1 题", hasPart("第 1/"))
        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitUntil(timeoutMillis = 10_000) { hasPart("第 2/") }
        println("  考试页左滑切题：第 1 题 → 第 2 题 ✓")
        composeRule.onRoot().performTouchInput { swipeRight() }
        composeRule.waitUntil(timeoutMillis = 10_000) { hasPart("第 1/") }
        println("  考试页右滑回退 ✓")

        // 考试模式点选项不会自动跳题，所以逐题作答直到最后一题
        composeRule.onAllNodesWithText("A")[0].performClick()
        var guard = 0
        while (!has("交卷") && guard++ < 80) {
            composeRule.onNodeWithText("下一题").performClick()
            composeRule.waitForIdle()
            runCatching { composeRule.onAllNodesWithText("A")[0].performClick() }
        }
        assertTrue("应该走到最后一题", has("交卷"))

        // 看一眼答题卡（右下角的「题目清单」）
        composeRule.onAllNodesWithContentDescription("题目清单")[0].performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { has("继续答题") }
        composeRule.onNodeWithText("继续答题").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("交卷").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { has("确定交卷") }
        composeRule.onNodeWithText("确定交卷").performClick()

        // 7. 成绩单
        composeRule.waitUntil(timeoutMillis = 30_000) { has("考试成绩") }
        composeRule.waitForIdle()
        shoot("06-考试成绩单.png")

        // 8. 考试记录：列表 + 逐题回顾
        composeRule.onNodeWithText("完成").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("我的题库").fetchSemanticsNodes().isNotEmpty()
        }
        clickTopBarMenu()
        composeRule.waitUntil(timeoutMillis = 10_000) { has("考试记录与上传") }
        composeRule.onNodeWithText("考试记录与上传").performClick()

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("本机考试概览").fetchSemanticsNodes().isNotEmpty()
        }
        // 未登录：只应该有「本机记录」一个标签页
        assertTrue("未登录应显示「本机记录」", has("本机记录"))
        assertTrue("未登录不该显示「云端记录」", !has("云端记录"))
        assertTrue("未登录不该显示「全部用户」", !has("全部用户"))
        println("  未登录时考试记录只有「本机记录」一个标签页 ✓")
        composeRule.waitForIdle()
        shoot("07-考试记录列表.png")

        composeRule.onAllNodesWithText("回顾题目")[0].performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("本次考试抽到的题目", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        shoot("08-考试记录逐题回顾.png")

        // 9. 系统返回键要逐级退：从「逐题回顾」返回应回到考试记录列表，而不是一下子回主界面
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(timeoutMillis = 20_000) { has("本机考试概览") }
        assertTrue("从逐题回顾返回不该直接回主界面", !has("我的题库"))
        println("  系统返回键：逐题回顾 → 考试记录列表 ✓")

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(timeoutMillis = 20_000) { has("我的题库") }
        println("  系统返回键：考试记录列表 → 主界面 ✓")
    }
}
