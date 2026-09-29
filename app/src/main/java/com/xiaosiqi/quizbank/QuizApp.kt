package com.xiaosiqi.quizbank

import android.app.Application
import android.content.Context
import com.xiaosiqi.quizbank.alist.AlistSession
import com.xiaosiqi.quizbank.data.QuizStore
import com.xiaosiqi.quizbank.data.db.SqliteQuizStore
import com.xiaosiqi.quizbank.data.prefs.SettingsStore
import com.xiaosiqi.quizbank.data.repo.BankRepository
import com.xiaosiqi.quizbank.image.ImageStore
import com.xiaosiqi.quizbank.exam.ExamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** 极简依赖容器：对象少，手动装配比引入 Hilt 更省事也更快。 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val settings: SettingsStore = SettingsStore(appContext)

    val store: QuizStore = SqliteQuizStore(appContext)

    /** alist 会话：题库、图片、成绩三处共用同一个登录态。 */
    val session: AlistSession = AlistSession(settings)

    /** 图片本地缓存（离线看图）。 */
    val images: ImageStore = ImageStore(File(appContext.filesDir, "images")) { session.authorized() }

    val repository: BankRepository = BankRepository(store, settings, session, images)

    val exams: ExamRepository = ExamRepository(store, settings) { session.authorized() }

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        images.limitBytes = settings.current().imageCacheLimitMb.coerceIn(20, 2000) * 1024L * 1024L
        appScope.launch {
            runCatching { images.trimTo() }
            // 修复早期版本算错的成绩目录（会落到 alist 根目录、用户名拼两次）
            runCatching {
                val fixed = settings.current().repaired()
                if (fixed != settings.current()) settings.edit { fixed }
            }
            // 启动时按登录状态把默认路径对齐一次（用户没手动改过才动）
            runCatching { session.applyAutoDefaults() }
        }
    }
}

class QuizApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as QuizApp).container
