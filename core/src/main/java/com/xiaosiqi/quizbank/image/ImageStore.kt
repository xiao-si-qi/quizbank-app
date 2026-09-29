package com.xiaosiqi.quizbank.image

import com.xiaosiqi.quizbank.alist.AlistClient
import com.xiaosiqi.quizbank.alist.FileRef
import com.xiaosiqi.quizbank.rich.RichText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 题目图片的本地缓存。
 *
 * 为什么不让 UI 直接加载网络图片：alist 上的图片往往需要 token 或临时签名，
 * 直接给图片库一个 URL 会 401；而且题库一旦下载到本地，用户期望离线也能看到图。
 * 所以这里统一「先查本地缓存 → 没有就用 AlistClient 拉 → 落盘 → 交给 UI 显示」。
 */
class ImageStore(
    private val root: File,
    private val clientProvider: suspend () -> AlistClient,
) {

    init {
        runCatching { root.mkdirs() }
    }

    /** 缓存键：相对路径要带上题库目录，否则两个题库里的 1.png 会串图。 */
    fun keyOf(ref: String, imageBase: String): String {
        val raw = "$imageBase\u0000${ref.trim()}"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun cached(ref: String, imageBase: String): File? {
        val key = keyOf(ref, imageBase)
        val candidates = IMAGE_EXTENSIONS.map { File(root, "$key.$it") }
        return candidates.firstOrNull { it.isFile && it.length() > 0 }
    }

    /**
     * 确保图片在本地。返回可直接显示的 File。
     * 网络失败时抛出带中文说明的异常，由 UI 显示占位。
     */
    suspend fun ensure(
        ref: String,
        imageBase: String,
        baseUrl: String,
        force: Boolean = false,
    ): File = withContext(Dispatchers.IO) {
        cached(ref, imageBase)?.takeIf { !force }?.let { return@withContext it }

        val client = clientProvider()
        val fileRef = RichText.toFileRef(ref, imageBase, baseUrl)
        val downloaded = client.download(fileRef)
        val bytes = downloaded.bytes
        if (bytes.isEmpty()) throw ImageException("图片为空：$ref")
        if (!looksLikeImage(bytes)) {
            throw ImageException("这个地址返回的不是图片：$ref\n（请检查 Excel 里的图片链接是否正确）")
        }

        val ext = extensionFor(bytes, ref)
        val key = keyOf(ref, imageBase)
        root.mkdirs()
        val target = File(root, "$key.$ext")
        val temp = File(root, "$key.tmp")
        temp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        trimTo(limitBytes)
        target
    }

    /** 预下载一批图片（导入题库时调用）。返回成功/失败数量。 */
    suspend fun prefetch(
        refs: Collection<Pair<String, String>>,
        baseUrl: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PrefetchResult {
        var ok = 0
        var failed = 0
        val errors = ArrayList<String>()
        val total = refs.size
        refs.forEachIndexed { index, (ref, imageBase) ->
            runCatching { ensure(ref, imageBase, baseUrl) }
                .onSuccess { ok++ }
                .onFailure {
                    failed++
                    if (errors.size < 5) errors.add("$ref：${it.message}")
                }
            onProgress(index + 1, total)
        }
        return PrefetchResult(ok, failed, errors)
    }

    data class PrefetchResult(val ok: Int, val failed: Int, val errors: List<String>)

    fun sizeBytes(): Long = root.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    fun clear() {
        root.listFiles()?.forEach { runCatching { it.delete() } }
    }

    var limitBytes: Long = 200L * 1024 * 1024

    /** 超出上限时按最后访问时间删除最旧的文件。 */
    fun trimTo(limit: Long = limitBytes) {
        val files = root.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        if (total <= limit) return
        for (file in files) {
            if (total <= limit) break
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= size
        }
    }

    private fun extensionFor(bytes: ByteArray, ref: String): String {
        magicExtension(bytes)?.let { return it }
        val fromRef = ref.substringBefore('?').substringAfterLast('.', "").lowercase()
        return if (fromRef.length in 2..5 && fromRef.all { it.isLetterOrDigit() }) fromRef else "img"
    }

    private fun magicExtension(bytes: ByteArray): String? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
            at(0) == 0xFF && at(1) == 0xD8 -> "jpg"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "gif"
            at(0) == 0x42 && at(1) == 0x4D -> "bmp"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 -> "webp"
            else -> null
        }
    }

    fun looksLikeImage(bytes: ByteArray): Boolean {
        if (magicExtension(bytes) != null) return true
        // SVG 是文本，检查开头是否是 XML/SVG
        val head = String(bytes, 0, minOf(256, bytes.size), Charsets.UTF_8).trimStart()
        return head.startsWith("<?xml") && head.contains("<svg", true) || head.startsWith("<svg", true)
    }

    companion object {
        private val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "img")
    }
}

class ImageException(message: String) : Exception(message)
