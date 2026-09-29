package com.xiaosiqi.quizbank.alist

import java.net.URI
import java.net.URLDecoder

/**
 * 文件地址的归一化。
 *
 * 用户会从各种地方复制地址：alist 相对路径、/d/ 直链、浏览器地址栏里的页面链接。
 * 这里把它统一成两种形态，后面的下载逻辑就不用再关心来源了。
 */
sealed class FileRef {

    /** 可以直接 GET 的完整地址（alist 的 /d/ 直链、或任意第三方 http 地址）。 */
    data class Direct(val url: String) : FileRef()

    /** alist 内部路径，需要通过 /api/fs/get 换取带签名的直链。 */
    data class AlistPath(val path: String) : FileRef()

    companion object {
        fun parse(raw: String, baseUrl: String): FileRef {
            val text = raw.trim().trim('"', '\'', ' ', '\u3000')
            require(text.isNotEmpty()) { "地址为空" }

            val base = baseUrl.trim().trimEnd('/')
            if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) {
                return AlistPath(normalizePath(text))
            }

            val uri = runCatching { URI(text) }.getOrNull()
                ?: return Direct(text)
            val baseUri = if (base.isEmpty()) null else runCatching { URI(base) }.getOrNull()

            val sameHost = baseUri != null &&
                uri.host.equals(baseUri.host, ignoreCase = true) &&
                effectivePort(uri) == effectivePort(baseUri)

            if (!sameHost) return Direct(text)

            var path = decode(uri.rawPath.orEmpty())
            val basePath = decode(baseUri.rawPath.orEmpty()).trimEnd('/')
            if (basePath.isNotEmpty() && path.startsWith(basePath)) path = path.removePrefix(basePath)
            if (path.isEmpty()) path = "/"

            // /d/ 与 /p/ 已经是直链；其余（浏览器地址栏里的页面链接）转成 alist 路径
            if (path.startsWith("/d/") || path.startsWith("/p/") || path.startsWith("/sd/")) {
                return Direct(text)
            }
            return AlistPath(normalizePath(path))
        }

        fun normalizePath(path: String): String {
            var p = decode(path.trim())
            p = p.replace('\\', '/')
            if (!p.startsWith("/")) p = "/$p"
            p = p.replace(Regex("/{2,}"), "/")
            // 解析 . 与 ..，并去掉尾部斜杠；避免出现 "/a/./b" 这种 alist 认不出的路径
            val parts = ArrayList<String>()
            for (segment in p.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(segment)
                }
            }
            return "/" + parts.joinToString("/")
        }

        /** 取所在目录，例如 /题库/网络/a.xlsx → /题库/网络 */
        fun dirOf(path: String): String {
            val p = normalizePath(path)
            val idx = p.lastIndexOf('/')
            return if (idx <= 0) "/" else p.substring(0, idx)
        }

        /**
         * 计算「题库文件所在的 alist 目录」，用于把题目里的相对图片路径拼成绝对路径。
         * 无论用户填的是 alist 路径、/d/ 直链还是浏览器地址栏链接，都尽量算对。
         */
        fun directoryOf(raw: String, baseUrl: String): String {
            if (raw.isBlank()) return ""
            val ref = runCatching { parse(raw, baseUrl) }.getOrNull() ?: return ""
            return when (ref) {
                is AlistPath -> dirOf(ref.path)
                is Direct -> {
                    val uri = runCatching { URI(ref.url) }.getOrNull() ?: return ""
                    var p = decode(uri.rawPath.orEmpty())
                    if (p.isBlank()) return ""
                    val basePath = if (baseUrl.isBlank()) "" else
                        runCatching { decode(URI(baseUrl.trim().trimEnd('/')).rawPath.orEmpty()) }.getOrDefault("").trim('/')
                    if (basePath.isNotEmpty() && p.startsWith("/$basePath")) p = p.removePrefix("/$basePath")
                    val segments = p.trim('/').split('/')
                    if (segments.size > 1 && segments[0] in setOf("d", "p", "sd")) {
                        p = "/" + segments.drop(1).joinToString("/")
                    }
                    dirOf(p)
                }
            }
        }

        private fun decode(s: String): String =
            runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        private fun effectivePort(uri: URI): Int = when {
            uri.port > 0 -> uri.port
            uri.scheme.equals("https", true) -> 443
            else -> 80
        }
    }
}
