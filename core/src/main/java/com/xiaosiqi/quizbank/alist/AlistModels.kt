package com.xiaosiqi.quizbank.alist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** alist 统一响应外壳：{ code, message, data }。 */
@Serializable
data class AlistEnvelope<T>(
    val code: Int = 0,
    val message: String = "",
    val data: T? = null,
)

@Serializable
data class AlistLoginData(val token: String = "")

/** GET /api/me 返回的账号信息。注意 role 在不同版本里可能是数字或数字数组。 */
@Serializable
data class AlistMeData(
    val id: Long = 0,
    val username: String = "",
    @SerialName("base_path") val basePath: String = "/",
    val role: JsonElement? = null,
    val permission: Long = 0,
    val disabled: Boolean = false,
)

/**
 * 当前 alist 账号。App 直接复用 alist 的权限体系：
 * 管理员能读到所有用户目录，普通用户只能读到自己有权限的路径，服务端强制校验。
 */
data class AlistAccount(
    val id: Long = 0,
    val username: String = "",
    val basePath: String = "/",
    val roles: List<Int> = emptyList(),
    val permission: Long = 0,
) {
    val isAdmin: Boolean get() = roles.contains(ROLE_ADMIN)

    /** 匿名访问时 alist 会返回 guest 账号。 */
    val isGuest: Boolean get() = roles.contains(ROLE_GUEST) || username.equals("guest", ignoreCase = true)

    val canWrite: Boolean get() = (permission shr 3) and 1L == 1L

    val roleLabel: String
        get() = when {
            isAdmin -> "管理员"
            isGuest -> "匿名访客"
            else -> "普通用户"
        }

    companion object {
        // AList 内部常量：GENERAL=0, GUEST=1, ADMIN=2, NEWGENERAL=3
        const val ROLE_GENERAL = 0
        const val ROLE_GUEST = 1
        const val ROLE_ADMIN = 2
        const val ROLE_NEW_GENERAL = 3
    }
}

@Serializable
data class AlistFsItem(
    val name: String = "",
    val size: Long = 0,
    @SerialName("is_dir") val isDir: Boolean = false,
    val modified: String = "",
    val created: String = "",
    val sign: String = "",
    val type: Int = 0,
    val thumb: String = "",
    val hashinfo: JsonElement? = null,
)

@Serializable
data class AlistFsListData(
    val content: List<AlistFsItem> = emptyList(),
    val total: Int = 0,
    val readme: String = "",
    /**
     * 不同版本/驱动返回的类型不一样：本机实测是空字符串 `""`，
     * 部分驱动会返回 JSON 对象。所以按 JsonElement 收，再统一解析成 Map。
     */
    val header: JsonElement? = null,
    val provider: String = "",
    val write: Boolean = false,
)

@Serializable
data class AlistFsGetData(
    val name: String = "",
    val size: Long = 0,
    @SerialName("is_dir") val isDir: Boolean = false,
    val modified: String = "",
    val created: String = "",
    @SerialName("raw_url") val rawUrl: String = "",
    val readme: String = "",
    val provider: String = "",
    val type: Int = 0,
    /** 同上：实测是空字符串，也可能是 JSON 对象。 */
    val header: JsonElement? = null,
)

@Serializable
data class AlistLoginRequest(val username: String, val password: String)

@Serializable
data class AlistRemoveRequest(
    val dir: String,
    val names: List<String>,
)

@Serializable
data class AlistPathRequest(
    val path: String,
    val password: String = "",
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = 0,
    val refresh: Boolean = false,
)

/** 下载好的文件内容。 */
class DownloadedFile(
    val bytes: ByteArray,
    val fileName: String = "",
    val contentType: String = "",
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

class AlistException(message: String, val code: Int = 0, cause: Throwable? = null) : Exception(message, cause)
