package com.xiaosiqi.quizbank.alist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * alist v3 / OpenList API 客户端。
 *
 * 只依赖 OkHttp，没有引入官方 SDK（那是给桌面/服务端用的）。
 * 所有错误都翻译成中文提示——用户看到「HTTP 404」是没用的，
 * 看到「alist 上找不到这个文件」才知道该怎么办。
 *
 * **关于路径**：alist 服务端的路径是相对于「当前账号的 base_path」的
 * （源码里就是 `path.Join(user.BasePath, reqPath)`）。所以给普通用户配置
 * `/题库/内部/a.xlsx` 时，实际请求要发 `/内部/a.xlsx`。
 * 这个换算在这里统一做掉，上层（题库、图片、成绩）只管用 alist 绝对路径，
 * 于是同一份 Excel 在管理员（base_path=/）和学生（base_path=/题库）下都能用。
 */
class AlistClient(
    val baseUrl: String,
    val token: String = "",
    /** 当前账号的 base_path，从 /api/me 拿；为空按根目录处理。 */
    val basePath: String = "/",
    private val http: OkHttpClient = defaultHttpClient(),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    val normalizedBase: String get() = baseUrl.trim().trimEnd('/')

    fun withBasePath(path: String): AlistClient =
        if (path == basePath) this else AlistClient(baseUrl, token, path, http)

    /**
     * 把「alist 绝对路径」换算成「相对于当前账号根目录的请求路径」。
     * 账号根目录是 / 时原样返回；不在自己根目录下的路径也原样返回（由服务端拒绝）。
     */
    internal fun requestPath(path: String): String {
        val base = basePath.trim().ifEmpty { "/" }.trimEnd('/')
        if (base.isEmpty()) return path
        val clean = if (path.startsWith("/")) path else "/$path"
        return when {
            clean == base -> "/"
            clean.startsWith("$base/") -> clean.removePrefix(base)
            else -> clean
        }
    }

    // -------------------------------------------------------------- 基础请求

    private fun apiUrl(path: String): String {
        val base = normalizedBase
        if (base.isEmpty()) throw AlistException("还没有配置 alist 服务器地址")
        return base + path
    }

    private fun requestBuilder(url: String): Request.Builder {
        val b = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        if (token.isNotBlank()) b.header("Authorization", token.trim())
        return b
    }

    private suspend fun postJson(path: String, body: String): String = withContext(Dispatchers.IO) {
        val request = requestBuilder(apiUrl(path))
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        execute(request, path)
    }

    private suspend fun getRaw(path: String): String = withContext(Dispatchers.IO) {
        execute(requestBuilder(apiUrl(path)).get().build(), path)
    }

    private fun execute(request: Request, what: String): String {
        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            throw AlistException("无法连接服务器：${e.message ?: e.javaClass.simpleName}\n请检查地址、端口和网络。", 0, e)
        }
        response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (looksLikeHtml(body)) {
                throw AlistException(
                    "服务器返回的是网页而不是接口数据。\n" +
                        "请确认「服务器地址」填的是 alist 首页地址（例如 https://pan.example.com 或 http://192.168.1.10:5244）。",
                    resp.code,
                )
            }
            if (!resp.isSuccessful) {
                throw AlistException("请求失败：HTTP ${resp.code}${errorHint(resp.code)}", resp.code)
            }
            if (body.isBlank()) throw AlistException("服务器返回了空数据（$what）")
            return body
        }
    }

    private inline fun <reified T> decode(body: String): AlistEnvelope<T> = try {
        json.decodeFromString<AlistEnvelope<T>>(body)
    } catch (e: Exception) {
        throw AlistException("无法解析 alist 返回的数据：${e.message}", 0, e)
    }

    // ------------------------------------------------------------------ 接口

    /** 测试连通性，返回形如「AList v3.36.0 · 我的网盘」的说明。 */
    suspend fun settings(): String {
        val body = getRaw("/api/public/settings")
        return runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            val version = obj["version"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val title = obj["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            listOfNotNull(version?.let { "AList $it" }, title).joinToString(" · ").ifBlank { "AList 连接正常" }
        }.getOrElse { "AList 连接正常" }
    }

    suspend fun login(username: String, password: String): String {
        val body = json.encodeToString(AlistLoginRequest.serializer(), AlistLoginRequest(username, password))
        val envelope = decode<AlistLoginData>(postJson("/api/auth/login", body))
        checkCode(envelope.code, envelope.message, "登录")
        return envelope.data?.token?.takeIf { it.isNotBlank() }
            ?: throw AlistException("登录成功但没有拿到 token")
    }

    /** 当前登录账号（用于判断是否管理员）。 */
    suspend fun me(): AlistAccount {
        val envelope = decode<AlistMeData>(getRaw("/api/me"))
        checkCode(envelope.code, envelope.message, "读取账号信息")
        val data = envelope.data ?: throw AlistException("没有拿到账号信息")
        val roles = when (val role = data.role) {
            null -> emptyList()
            is JsonArray -> role.mapNotNull { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            else -> listOfNotNull(runCatching { role.jsonPrimitive.intOrNull }.getOrNull())
        }
        return AlistAccount(
            id = data.id,
            username = data.username,
            basePath = data.basePath,
            roles = roles,
            permission = data.permission,
        )
    }

    suspend fun list(path: String, password: String = ""): List<AlistFsItem> {
        val body = json.encodeToString(
            AlistPathRequest.serializer(),
            AlistPathRequest(path = requestPath(path), password = password),
        )
        val envelope = decode<AlistFsListData>(postJson("/api/fs/list", body))
        checkCode(envelope.code, envelope.message, "读取目录 $path")
        val items = envelope.data?.content.orEmpty()
        return items.sortedWith(
            compareByDescending<AlistFsItem> { it.isDir }.thenBy { naturalKey(it.name) },
        )
    }

    suspend fun fsGet(path: String, password: String = ""): AlistFsGetData {
        val body = json.encodeToString(
            AlistPathRequest.serializer(),
            AlistPathRequest(path = requestPath(path), password = password),
        )
        val envelope = decode<AlistFsGetData>(postJson("/api/fs/get", body))
        checkCode(envelope.code, envelope.message, "获取文件 $path")
        return envelope.data ?: throw AlistException("alist 没有返回文件信息：$path")
    }

    /** 把 FileRef 变成真正的字节流。 */
    suspend fun download(ref: FileRef, password: String = ""): DownloadedFile = downloadWithUrl(ref, password).first

    /** 同时返回最终使用的直链，便于记录「上次从哪个地址导入」。 */
    suspend fun downloadWithUrl(ref: FileRef, password: String = ""): Pair<DownloadedFile, String> = when (ref) {
        is FileRef.Direct -> fetchBytes(ref.url, emptyMap()) to ref.url
        is FileRef.AlistPath -> {
            val info = fsGet(ref.path, password)
            if (info.isDir) throw AlistException("「${ref.path}」是目录而不是文件")
            val url = resolveRawUrl(info.rawUrl, ref.path)
            fetchBytes(url, parseHeader(info.header) + authHeaders()) to url
        }
    }

    /**
     * fs/get、fs/list 返回的 `header` 在不同版本里可能是空字符串、JSON 字符串或 JSON 对象。
     * 统一解析成「下载时要带的请求头」。
     */
    internal fun parseHeader(element: JsonElement?): Map<String, String> {
        if (element == null) return emptyMap()
        return when (element) {
            is JsonObject -> element.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
            is JsonPrimitive -> {
                val text = element.contentOrNull.orEmpty().trim()
                if (text.isEmpty() || text == "null") emptyMap()
                else runCatching {
                    json.parseToJsonElement(text).jsonObject
                        .mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
                }.getOrDefault(emptyMap())
            }
            else -> emptyMap()
        }
    }

    suspend fun fetchBytes(url: String, headers: Map<String, String> = emptyMap()): DownloadedFile =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) builder.header(k, v) }
            val response = try {
                http.newCall(builder.build()).execute()
            } catch (e: Exception) {
                throw AlistException("下载失败：${e.message ?: e.javaClass.simpleName}", 0, e)
            }
            response.use { resp ->
                val bytes = resp.body?.bytes() ?: throw AlistException("下载内容为空")
                if (!resp.isSuccessful) {
                    throw AlistException("下载失败：HTTP ${resp.code}${errorHint(resp.code)}", resp.code)
                }
                if (looksLikeHtml(String(bytes, 0, minOf(512, bytes.size), Charsets.UTF_8))) {
                    throw AlistException(
                        "下载到的是网页而不是文件。\n" +
                            "通常是因为用了浏览器的预览地址，请改用 alist 的 /d/ 直链或 alist 内部路径。",
                        resp.code,
                    )
                }
                DownloadedFile(bytes, fileNameFrom(resp.header("Content-Disposition"), url), resp.header("Content-Type").orEmpty())
            }
        }

    private fun authHeaders(): Map<String, String> =
        if (token.isNotBlank()) mapOf("Authorization" to token.trim()) else emptyMap()

    // ------------------------------------------------------------ 写入接口

    /** 新建目录（已存在算成功）。 */
    suspend fun mkdir(path: String) {
        mkdirRequest(requestPath(FileRef.normalizePath(path)), path)
    }

    private suspend fun mkdirRequest(requestPath: String, displayPath: String) {
        if (requestPath == "/") return
        val body = json.encodeToString(AlistPathRequest.serializer(), AlistPathRequest(path = requestPath))
        postAction("/api/fs/mkdir", body, "新建目录 $displayPath")
    }

    /** 确保目录存在，逐级创建（路径换算成请求路径后再逐级建，避免重复拼接 base_path）。 */
    suspend fun ensureDir(path: String) {
        val request = requestPath(FileRef.normalizePath(path))
        if (request == "/") return
        val existing = runCatching { fsGet(path) }.getOrNull()
        if (existing?.isDir == true) return
        var current = ""
        for (segment in request.trim('/').split('/')) {
            if (segment.isBlank()) continue
            current += "/$segment"
            // 目录已存在时 alist 会报错，这里直接忽略
            runCatching { mkdirRequest(current, current) }
        }
    }

    /**
     * 上传文件（流式 PUT /api/fs/put）。
     * File-Path 必须是 URL 编码后的完整路径，且同样相对于账号根目录。
     */
    suspend fun upload(path: String, bytes: ByteArray, contentType: String = "application/octet-stream") {
        withContext(Dispatchers.IO) {
            val request = requestPath(FileRef.normalizePath(path))
            // alist 要求 File-Path 是「URL 编码后的完整路径」：
            // 斜杠作为分隔符保留，空格用 %20 而不是 +（更符合路径的编码习惯）
            val encoded = URLEncoder.encode(request, "UTF-8")
                .replace("+", "%20")
                .replace("%2F", "/")
            val httpRequest = requestBuilder(apiUrl("/api/fs/put"))
                .header("File-Path", encoded)
                .header("Content-Type", contentType)
                .put(bytes.toRequestBody(contentType.toMediaType()))
                .build()
            val text = execute(httpRequest, "上传 $path")
            val (code, message) = decodeEnvelope(text)
            checkCode(code, message, "上传 $path")
        }
    }

    /**
     * 删除一个文件或目录。
     * alist 的删除接口要的是「父目录 + 要删的名字」，这里把完整路径拆开。
     */
    suspend fun remove(path: String) {
        val clean = FileRef.normalizePath(path).trimEnd('/')
        if (clean.isEmpty() || clean == "/") return
        val dir = clean.substringBeforeLast('/').ifBlank { "/" }
        val name = clean.substringAfterLast('/')
        if (name.isBlank()) return
        val body = json.encodeToString(
            AlistRemoveRequest.serializer(),
            AlistRemoveRequest(dir = requestPath(dir), names = listOf(name)),
        )
        postAction("/api/fs/remove", body, "删除 $path")
    }

    /** 上传文本（内部按 UTF-8 编码）。 */
    suspend fun uploadText(path: String, text: String, contentType: String = "application/json; charset=utf-8") {
        upload(path, text.toByteArray(Charsets.UTF_8), contentType)
    }

    suspend fun exists(path: String): Boolean = runCatching { fsGet(path) }.isSuccess

    private suspend fun postAction(path: String, body: String, action: String) {
        val text = postJson(path, body)
        val (code, message) = decodeEnvelope(text)
        checkCode(code, message, action)
    }

    private fun decodeEnvelope(text: String): Pair<Int, String> = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        val code = obj["code"]?.jsonPrimitive?.intOrNull ?: 0
        val message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        code to message
    }.getOrElse { throw AlistException("无法解析 alist 返回的数据：${it.message}", 0, it) }

    fun resolveRawUrl(rawUrl: String, fallbackPath: String = ""): String {
        if (rawUrl.isBlank()) throw AlistException("alist 没有返回下载地址${if (fallbackPath.isNotEmpty()) "：$fallbackPath" else ""}")
        if (rawUrl.startsWith("http://", true) || rawUrl.startsWith("https://", true)) return rawUrl
        val base = normalizedBase
        if (base.isEmpty()) throw AlistException("还没有配置 alist 服务器地址")
        return if (rawUrl.startsWith("/")) base + rawUrl else "$base/$rawUrl"
    }

    private fun checkCode(code: Int, message: String, action: String) {
        if (code == 200) return
        val hint = when (code) {
            401 -> "需要登录：请在「设置」里填写 alist 的用户名密码，或直接粘贴 Token。"
            403 -> "没有权限访问该路径。"
            404 -> "alist 上找不到这个路径。"
            else -> message
        }
        throw AlistException("$action 失败（code=$code）：$hint", code)
    }

    private fun errorHint(code: Int): String = when (code) {
        401, 403 -> "（需要登录或没有权限）"
        404 -> "（文件不存在）"
        in 500..599 -> "（服务器错误）"
        else -> ""
    }

    private fun looksLikeHtml(text: String): Boolean {
        val t = text.trimStart().lowercase()
        return t.startsWith("<!doctype html") || t.startsWith("<html") || t.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?><html")
    }

    private fun fileNameFrom(disposition: String?, url: String): String {
        if (!disposition.isNullOrBlank()) {
            Regex("filename\\*=UTF-8''([^;]+)").find(disposition)?.let {
                return runCatching { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }.getOrDefault(it.groupValues[1])
            }
            Regex("filename=\"?([^\";]+)\"?").find(disposition)?.let { return it.groupValues[1].trim() }
        }
        val clean = url.substringBefore('?').trimEnd('/')
        val last = clean.substringAfterLast('/')
        return runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
    }

    companion object {
        private const val USER_AGENT = "QuizBank/1.0 (Android)"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        /** 用自然序排序文件名，让「第2章」排在「第10章」前面。 */
        fun naturalKey(name: String): String {
            val sb = StringBuilder(name.length + 8)
            var i = 0
            while (i < name.length) {
                val ch = name[i]
                if (ch.isDigit()) {
                    var j = i
                    while (j < name.length && name[j].isDigit()) j++
                    val num = name.substring(i, j)
                    sb.append(num.padStart(10, '0'))
                    i = j
                } else {
                    sb.append(ch.lowercaseChar())
                    i++
                }
            }
            return sb.toString()
        }

        fun isSupportedFile(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".csv") ||
                lower.endsWith(".txt") || lower.endsWith(".tsv")
        }
    }
}
