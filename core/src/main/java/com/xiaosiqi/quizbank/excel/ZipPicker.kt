package com.xiaosiqi.quizbank.excel

import java.util.zip.ZipInputStream

/** 从内存中的 zip（xlsx 本质是 zip）里挑出需要的条目，忽略图片、绘图等无关内容。 */
internal object ZipPicker {

    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    fun pick(bytes: ByteArray, maxTotal: Long = 256L * 1024 * 1024, keep: (String) -> Boolean): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                if (entry.isDirectory || !keep(name)) {
                    zip.closeEntry()
                    continue
                }
                val data = zip.readBytesCapped(MAX_ENTRY_BYTES)
                if (data != null) {
                    total += data.size
                    if (total > maxTotal) break
                    out[name] = data
                }
                zip.closeEntry()
            }
        }
        return out
    }

    private fun ZipInputStream.readBytesCapped(cap: Long): ByteArray? {
        val chunk = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream(64 * 1024)
        while (true) {
            val n = read(chunk)
            if (n <= 0) break
            out.write(chunk, 0, n)
            if (out.size() > cap) return null
        }
        return out.toByteArray()
    }
}
