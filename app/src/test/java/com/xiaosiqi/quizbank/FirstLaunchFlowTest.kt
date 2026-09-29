package com.xiaosiqi.quizbank

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
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

/**
 * 首次启动流程：默认设置下自动去线上取题库；**取不到就落到内置题库**。
 *
 * 这里用一个 MockWebServer 假装「服务器在，但文件读不到」，
 * 这样兜底这条路是确定性的，也不依赖外网。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FirstLaunchFlowTest {

    private val server = MockWebServer()

    private val prepareServer = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                // 任何请求都回「对象不存在」，模拟题库读不到的情况
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"code":404,"message":"object not found","data":null}""")
                }
                server.start()

                val context = ApplicationProvider.getApplicationContext<Context>()
                // 同一个测试 JVM 里数据目录是共用的，先清干净，保证走的是真正的「首次启动」
                File(context.applicationInfo.dataDir, "databases").deleteRecursively()
                File(context.filesDir, "images").deleteRecursively()
                // 用 App 自己的设置实例写入，否则 StateFlow 里还是旧值
                (context.applicationContext as QuizApp).container.settings.edit {
                    it.copy(
                        baseUrl = server.url("/").toString().trimEnd('/'),
                        listPath = "/题库/公开/题库清单-公开.xlsx",
                        autoConfigurePaths = false,
                        useBuiltinFallback = true,
                    )
                }
                base.evaluate()
            }
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(prepareServer).around(composeRule)

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `在线题库读不到时自动落到内置题库并能直接练习`() {
        val waited = runCatching {
            composeRule.waitUntil(timeoutMillis = 60_000) {
                composeRule.onAllNodesWithText("综合练习（内置示例）").fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (waited.isFailure) {
            // 失败时把界面树 + 数据库内容 + 当前设置 dump 出来，方便定位
            java.io.File("/tmp/tree.txt").writeText(composeRule.onRoot().printToString(maxDepth = 100))
        }
        waited.getOrThrow()
        // 兜底成功时会弹一个说明对话框，先关掉再断言卡片可交互
        runCatching { composeRule.onNodeWithText("好的").performClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("顺序练习").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("随机练习").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `未登录时首页给出登录入口`() {
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithText("登录后可获取更多题库").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("去登录").assertIsDisplayed()
    }

    @Test
    fun `点去登录能进入只有三个字段的登录页`() {
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithText("去登录").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("去登录").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("用户名").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("服务器地址").assertIsDisplayed()
        composeRule.onNodeWithText("用户名").assertIsDisplayed()
        composeRule.onNodeWithText("密码").assertIsDisplayed()
        // 「登录」既是页面标题也是按钮，所以可能有多个
        assertTrue(
            "登录页应该有登录按钮",
            composeRule.onAllNodesWithText("登录").fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
