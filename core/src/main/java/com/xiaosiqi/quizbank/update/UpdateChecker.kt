package com.xiaosiqi.quizbank.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 一个新版本（来自 GitHub Releases）。 */
data class AppRelease(
    /** 去掉 v 前缀的版本号，如 1.0.1 */
    val version: String,
    val tag: String,
    val name: String = "",
    val notes: String = "",
    /** 优先直接下 APK；拿不到就打开发行页 */
    val apkUrl: String = "",
    val pageUrl: String = "",
    val publishedAt: String = "",
    val sizeBytes: Long = 0L,
) {
    val downloadUrl: String get() = apkUrl.ifBlank { pageUrl }
    val sizeText: String
        get() = if (sizeBytes <= 0) "" else "%.1f MB".format(sizeBytes / 1048576.0)
}

/**
 * 检查 GitHub Releases 有没有新版本。
 *
 * 仓库写死成本项目自己的。GitHub 对匿名 API 调用有每小时 60 次的限制，
 * 所以**自动检查做了节流**（一天一次，见 HomeViewModel），手动检查不受限制。
 */
class UpdateChecker(
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** 查最新发行版；没有发行版、没网、被限流都返回 null（静默失败）。 */
    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "quizbank-app")
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parse(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    private fun parse(body: String): AppRelease? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val assets = runCatching { root["assets"]?.jsonArray }.getOrNull().orEmpty()
        val apks = assets.filter {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", true) == true
        }
        // 优先选文件名里带 release 的那个（debug 包体积大且更慢）
        val apk = apks.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.contains("release", true) == true
        } ?: apks.firstOrNull()

        return AppRelease(
            version = tag.trimStart('v', 'V'),
            tag = tag,
            name = root["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            apkUrl = apk?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.contentOrNull.orEmpty(),
            pageUrl = root["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            sizeBytes = apk?.jsonObject?.get("size")?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    companion object {
        const val DEFAULT_OWNER = "xiao-si-qi"
        const val DEFAULT_REPO = "quizbank-app"

        /** 自动检查的最小间隔：一天 */
        const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        /**
         * 版本号比较。按「段」比数字，所以 1.0.10 比 1.0.9 新
         * （字符串比较会得出相反结论，这是个常见坑）。
         */
        fun isNewer(current: String, latest: String): Boolean {
            fun parts(v: String) = v.trim().trimStart('v', 'V').split('.', '-', '+', '_')
            val a = parts(current)
            val b = parts(latest)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrNull(i)?.toIntOrNull() ?: 0
                val y = b.getOrNull(i)?.toIntOrNull() ?: 0
                if (y != x) return y > x
            }
            return false
        }
    }
}
