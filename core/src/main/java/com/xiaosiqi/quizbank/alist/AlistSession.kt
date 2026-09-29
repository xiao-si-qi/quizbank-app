package com.xiaosiqi.quizbank.alist

import com.xiaosiqi.quizbank.data.prefs.AppSettings
import com.xiaosiqi.quizbank.data.prefs.SettingsGateway

/**
 * alist 会话：负责「拿到一个可用的 AlistClient」。
 *
 * 单独抽出来是因为题库、图片、成绩三处都要用同一个登录态，
 * 而且 token 过期后需要重新登录（用 alist 自己的账号体系）。
 *
 * 这里还负责把账号的 base_path 带进客户端：alist 的路径相对于它，
 * 上层只管用绝对路径，换算在 AlistClient 里做。
 */
class AlistSession(private val settings: SettingsGateway) {

    private var cachedAccount: AlistAccount? = null

    /** 不带登录态、也不查询账号的原始客户端（内部用，避免递归）。 */
    private fun plainClient(): AlistClient {
        val s = settings.current()
        return AlistClient(s.baseUrl, s.token)
    }

    fun client(): AlistClient = plainClient()

    /** 有账号密码但没 token 时先登录一次；匿名场景直接返回空 token 的客户端。 */
    private suspend fun ensureClient(): AlistClient {
        val s = settings.current()
        if (s.baseUrl.isBlank()) {
            throw AlistException("还没有配置 alist 服务器地址，请先到「设置」里填写。")
        }
        if (s.token.isNotBlank() || s.username.isBlank() || s.password.isBlank()) {
            return AlistClient(s.baseUrl, s.token)
        }
        val token = AlistClient(s.baseUrl, "").login(s.username, s.password)
        settings.edit { it.copy(token = token) }
        cachedAccount = null
        return AlistClient(s.baseUrl, token)
    }

    /** 带 base_path 的可用客户端。 */
    suspend fun authorized(): AlistClient {
        val client = ensureClient()
        val basePath = me()?.basePath ?: "/"
        return client.withBasePath(basePath)
    }

    /**
     * 带一次重试的调用：token 失效（401）时重新登录再试一次。
     * 用户长时间不打开 App 时 token 很可能已经过期，静默重登体验最好。
     */
    suspend fun <T> withAuth(forceLogin: Boolean = false, block: suspend (AlistClient) -> T): T {
        val s = settings.current()
        if (forceLogin) {
            val token = login(s.baseUrl, s.username, s.password)
            cachedAccount = null
            return block(AlistClient(s.baseUrl, token).withBasePath(me(force = true)?.basePath ?: "/"))
        }
        val client = authorized()
        return try {
            block(client)
        } catch (e: AlistException) {
            val canRelogin = e.code == 401 && s.username.isNotBlank() && s.password.isNotBlank()
            if (!canRelogin) throw e
            val token = login(s.baseUrl, s.username, s.password)
            cachedAccount = null
            block(AlistClient(s.baseUrl, token).withBasePath(me(force = true)?.basePath ?: "/"))
        }
    }

    suspend fun testConnection(baseUrl: String, token: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        if (clean.isEmpty()) throw AlistException("请先填写 alist 服务器地址")
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) {
            throw AlistException("服务器地址需要以 http:// 或 https:// 开头")
        }
        return AlistClient(clean, token).settings()
    }

    suspend fun login(baseUrl: String, username: String, password: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        if (clean.isEmpty()) throw AlistException("请填写 alist 服务器地址")
        if (username.isBlank()) throw AlistException("请填写用户名")
        if (password.isBlank()) throw AlistException("请填写密码")
        val token = AlistClient(clean, "").login(username, password)
        settings.edit {
            it.copy(baseUrl = clean, token = token, username = username, password = password)
        }
        cachedAccount = null
        // 登录成功后自动切到「完整题库清单 + 自己的成绩目录」，普通用户不用管路径
        applyAutoDefaults()
        return token
    }

    fun logout() {
        settings.edit {
            it.copy(token = "", username = "", password = "", displayName = "").withAutoDefaults(null)
        }
        cachedAccount = null
    }

    /**
     * 按当前登录状态刷新默认路径（题库列表、成绩目录）。
     * 用户手动改过路径的话 [AppSettings.autoConfigurePaths] 已经是 false，不会被动到。
     * @return 是否真的改了设置
     */
    suspend fun applyAutoDefaults(): Boolean {
        val before = settings.current()
        val account = me(force = true)
        val after = before.withAutoDefaults(account)
        if (after != before) settings.edit { after }
        return after != before
    }

    /** 当前账号信息（带缓存，失败返回 null 不抛异常）。匿名场景会返回 null。 */
    suspend fun me(force: Boolean = false): AlistAccount? {
        if (!force) cachedAccount?.let { return it }
        val s = settings.current()
        if (s.baseUrl.isBlank()) return null
        val account = runCatching { plainClient().me() }.getOrNull() ?: return null
        cachedAccount = account
        return account
    }

    fun cachedAccount(): AlistAccount? = cachedAccount
}
