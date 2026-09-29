package com.xiaosiqi.quizbank.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 端设置存储：SharedPreferences + StateFlow。
 * 设置项少且需要在发请求前同步读取，所以没用 DataStore。
 *
 * 注意：服务器地址、题库列表地址都有默认值，用户不清空就一直是可用的。
 */
class SettingsStore(context: Context) : SettingsGateway {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("quizbank_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    /**
     * 监听底层 SharedPreferences 的变化。
     * 设置被别处（另一个实例、测试、将来的跨进程写入）改动时也能立刻同步，
     * 不至于因为 StateFlow 缓存了旧值而读到过期设置。
     */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _state.value = read()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /** 强制从磁盘重读一次。 */
    fun reload() {
        _state.value = read()
    }

    override fun current(): AppSettings = _state.value

    override fun edit(block: (AppSettings) -> AppSettings) {
        val next = block(_state.value)
        prefs.edit().apply {
            putString(KEY_BASE_URL, next.baseUrl.trim().ifBlank { AppSettings.DEFAULT_BASE_URL })
            putString(KEY_TOKEN, next.token.trim())
            putString(KEY_USERNAME, next.username.trim())
            putString(KEY_PASSWORD, next.password)
            putString(KEY_LIST_PATH, next.listPath.trim())
            putString(KEY_LIST_PASSWORD, next.listPassword)
            putBoolean(KEY_AUTO_SYNC, next.autoSyncOnStart)
            putBoolean(KEY_SHUFFLE_Q, next.shuffleQuestions)
            putBoolean(KEY_SHUFFLE_O, next.shuffleOptions)
            putBoolean(KEY_AUTO_REMOVE_WRONG, next.autoRemoveWrong)
            putBoolean(KEY_SHOW_ANALYSIS, next.showAnalysisImmediately)
            putInt(KEY_THEME, next.darkTheme)
            putFloat(KEY_FONT_SCALE, next.fontScale)
            putInt(KEY_DAILY_GOAL, next.dailyGoal)
            putLong(KEY_LAST_SYNC, next.lastSyncAt)
            putString(KEY_LAST_SYNC_SUMMARY, next.lastSyncSummary)
            putBoolean(KEY_DL_IMAGES, next.downloadImagesWithBank)
            putBoolean(KEY_DL_IMAGES_WIFI, next.downloadImagesWifiOnly)
            putInt(KEY_IMAGE_CACHE_MB, next.imageCacheLimitMb)
            putString(KEY_SCORE_PATH, next.scoreUploadPath.trim())
            putBoolean(KEY_AUTO_UPLOAD_SCORE, next.autoUploadScore)
            putString(KEY_DISPLAY_NAME, next.displayName.trim())
            putBoolean(KEY_ADMIN_OVERVIEW, next.allowAdminOverview)
            putBoolean(KEY_AUTO_CONFIGURE, next.autoConfigurePaths)
            putBoolean(KEY_BUILTIN_FALLBACK, next.useBuiltinFallback)
        }.apply()
        _state.value = next
    }

    private fun read(): AppSettings = AppSettings(
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty().ifBlank { AppSettings.DEFAULT_BASE_URL },
        token = prefs.getString(KEY_TOKEN, "").orEmpty(),
        username = prefs.getString(KEY_USERNAME, "").orEmpty(),
        password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
        listPath = prefs.getString(KEY_LIST_PATH, "").orEmpty().ifBlank { AppSettings.DEFAULT_PUBLIC_LIST },
        listPassword = prefs.getString(KEY_LIST_PASSWORD, "").orEmpty(),
        autoSyncOnStart = prefs.getBoolean(KEY_AUTO_SYNC, false),
        shuffleQuestions = prefs.getBoolean(KEY_SHUFFLE_Q, false),
        shuffleOptions = prefs.getBoolean(KEY_SHUFFLE_O, false),
        autoRemoveWrong = prefs.getBoolean(KEY_AUTO_REMOVE_WRONG, true),
        showAnalysisImmediately = prefs.getBoolean(KEY_SHOW_ANALYSIS, true),
        darkTheme = prefs.getInt(KEY_THEME, 0),
        fontScale = prefs.getFloat(KEY_FONT_SCALE, 1f),
        dailyGoal = prefs.getInt(KEY_DAILY_GOAL, 20),
        lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L),
        lastSyncSummary = prefs.getString(KEY_LAST_SYNC_SUMMARY, "").orEmpty(),
        downloadImagesWithBank = prefs.getBoolean(KEY_DL_IMAGES, true),
        downloadImagesWifiOnly = prefs.getBoolean(KEY_DL_IMAGES_WIFI, false),
        imageCacheLimitMb = prefs.getInt(KEY_IMAGE_CACHE_MB, 200),
        scoreUploadPath = prefs.getString(KEY_SCORE_PATH, "").orEmpty(),
        autoUploadScore = prefs.getBoolean(KEY_AUTO_UPLOAD_SCORE, false),
        displayName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty(),
        allowAdminOverview = prefs.getBoolean(KEY_ADMIN_OVERVIEW, true),
        autoConfigurePaths = prefs.getBoolean(KEY_AUTO_CONFIGURE, true),
        useBuiltinFallback = prefs.getBoolean(KEY_BUILTIN_FALLBACK, true),
    )

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "token"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_LIST_PATH = "list_path"
        const val KEY_LIST_PASSWORD = "list_password"
        const val KEY_AUTO_SYNC = "auto_sync"
        const val KEY_SHUFFLE_Q = "shuffle_questions"
        const val KEY_SHUFFLE_O = "shuffle_options"
        const val KEY_AUTO_REMOVE_WRONG = "auto_remove_wrong"
        const val KEY_SHOW_ANALYSIS = "show_analysis"
        const val KEY_THEME = "theme"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_DAILY_GOAL = "daily_goal"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_LAST_SYNC_SUMMARY = "last_sync_summary"
        const val KEY_DL_IMAGES = "download_images"
        const val KEY_DL_IMAGES_WIFI = "download_images_wifi"
        const val KEY_IMAGE_CACHE_MB = "image_cache_mb"
        const val KEY_SCORE_PATH = "score_path"
        const val KEY_AUTO_UPLOAD_SCORE = "auto_upload_score"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_ADMIN_OVERVIEW = "admin_overview"
        const val KEY_AUTO_CONFIGURE = "auto_configure_paths"
        const val KEY_BUILTIN_FALLBACK = "builtin_fallback"
    }
}
