package com.xiaosiqi.quizbank.data.prefs

import com.xiaosiqi.quizbank.alist.AlistAccount

/**
 * 全局设置的纯数据模型。
 *
 * 设计原则：**普通用户不需要配置任何东西**。
 * 服务器地址、题库列表地址、成绩目录都有默认值，跟着登录状态自动切换；
 * 只有想接自己的 alist 时才需要改。
 */
data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val token: String = "",
    val username: String = "",
    val password: String = "",
    /** 题库列表 Excel 的地址（alist 路径或直链）。 */
    val listPath: String = DEFAULT_PUBLIC_LIST,
    val listPassword: String = "",
    val autoSyncOnStart: Boolean = false,
    val shuffleQuestions: Boolean = false,
    val shuffleOptions: Boolean = false,
    val autoRemoveWrong: Boolean = true,
    val showAnalysisImmediately: Boolean = true,
    /** 0 跟随系统 / 1 浅色 / 2 深色 */
    val darkTheme: Int = 0,
    val fontScale: Float = 1f,
    val dailyGoal: Int = 20,
    val lastSyncAt: Long = 0L,
    val lastSyncSummary: String = "",

    // ---- 图片 ----
    val downloadImagesWithBank: Boolean = true,
    val downloadImagesWifiOnly: Boolean = false,
    val imageCacheLimitMb: Int = 200,

    // ---- 成绩 ----
    /** 成绩上传到 alist 的目录，例如 /题库/成绩/张三。 */
    val scoreUploadPath: String = "",
    /**
     * 自动把考试记录同步到云端。
     * **默认开启**：登录用户在考试过程中就会节流同步（可跨设备续考），交卷后立即同步一次。
     * 普通练习不产生成绩，自然也不上传。
     */
    val autoUploadScore: Boolean = true,
    val displayName: String = "",
    /** 是否允许管理员查看所有人的考试记录。 */
    val allowAdminOverview: Boolean = true,
    /** 上次自动检查更新的时间（一天只自动查一次） */
    val lastUpdateCheckAt: Long = 0L,
    /** 用户点了「跳过此版本」的版本号 */
    val skippedVersion: String = "",

    // ---- 普通用户的开箱即用策略 ----
    /** 题库列表地址与成绩目录是否跟随登录状态自动设置（用户手动改过就关掉）。 */
    val autoConfigurePaths: Boolean = true,
    /** 在线题库打不开时，自动载入内置示例题库。 */
    val useBuiltinFallback: Boolean = true,
) {
    /** 是否已登录（有 token 才算）。 */
    val loggedIn: Boolean get() = token.isNotBlank()

    /** 是否填了服务器地址（有默认值，基本恒为 true）。 */
    val configured: Boolean get() = baseUrl.isNotBlank()

    val hasBankListSource: Boolean get() = listPath.isNotBlank()

    val hasScorePath: Boolean get() = scoreUploadPath.isNotBlank()

    /** 成绩署名：优先用自定义名字，其次 alist 用户名，最后「匿名」。 */
    val effectiveName: String
        get() = displayName.ifBlank { username }.ifBlank { "匿名" }

    /**
     * 按登录状态套用默认路径。
     *
     * - 未登录（匿名）：只能看公开题库，成绩无处可传
     * - 已登录：切到完整题库清单，成绩目录指向**所有人共用的成绩根目录**
     *   （[/DEFAULT_SCORE_DIR]），每个用户的具体目录由 [ExamRepository] 在根目录下
     *   按用户名建子目录。
     *
     * 为什么不按账号的 base_path 去算成绩目录：
     * alist 的 base_path 各不相同（管理员是 `/`，学生可能是 `/题库`），
     * 按它算出来的目录会落在各人自己的根下，管理员就看不到别人的成绩了。
     * 统一用绝对路径 `/题库/成绩`，客户端会自动按 base_path 换算（见 AlistClient.requestPath）。
     */
    fun withAutoDefaults(account: AlistAccount?): AppSettings {
        if (!autoConfigurePaths) return this
        val loggedIn = account != null && !account.isGuest && token.isNotBlank()
        return copy(
            baseUrl = baseUrl.ifBlank { DEFAULT_BASE_URL },
            listPath = if (loggedIn) DEFAULT_FULL_LIST else DEFAULT_PUBLIC_LIST,
            scoreUploadPath = if (loggedIn) DEFAULT_SCORE_DIR else "",
            displayName = displayName.ifBlank { if (loggedIn) account.username else "" },
        )
    }

    /**
     * 修复历史 bug 留下的成绩目录。
     *
     * 早期版本按账号 base_path 拼成绩目录，管理员（base_path=`/`）会算成
     * `/成绩/用户名`；而上传时又拼了一次用户名，最终传到了
     * `/成绩/用户名/用户名/`。这里把这种残留值改回默认的成绩根目录。
     */
    fun repaired(): AppSettings {
        val path = scoreUploadPath.trim()
        val broken = path == "/$DEFAULT_SCORE_SUBDIR" || path.startsWith("/$DEFAULT_SCORE_SUBDIR/")
        return if (broken) copy(scoreUploadPath = DEFAULT_SCORE_DIR) else this
    }

    companion object {
        /** 默认的 alist 服务器：不填也能直接用公开题库。 */
        const val DEFAULT_BASE_URL = "https://alist.xiaosiqi.cn"

        /** 匿名可读的题库清单。 */
        const val DEFAULT_PUBLIC_LIST = "/题库/公开/题库清单-公开.xlsx"

        /** 登录后可见的完整题库清单。 */
        const val DEFAULT_FULL_LIST = "/题库/题库清单-全部.xlsx"

        /**
         * 成绩根目录：所有人共用，每个用户在下面建自己的子目录。
         * 用绝对路径，客户端会按各自账号的 base_path 自动换算。
         */
        const val DEFAULT_SCORE_DIR = "/题库/成绩"

        /** 成绩子目录名，仅用于识别历史 bug 留下的路径。 */
        const val DEFAULT_SCORE_SUBDIR = "成绩"
    }
}

/** 设置的读写入口。Android 端用 SharedPreferences 实现，测试里用内存实现。 */
interface SettingsGateway {
    fun current(): AppSettings

    fun edit(block: (AppSettings) -> AppSettings)
}

/** 内存实现，方便单元测试。 */
class InMemorySettings(initial: AppSettings = AppSettings()) : SettingsGateway {
    private var value = initial

    override fun current(): AppSettings = value

    override fun edit(block: (AppSettings) -> AppSettings) {
        value = block(value)
    }
}
