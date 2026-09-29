package com.xiaosiqi.quizbank.rich

import com.xiaosiqi.quizbank.alist.FileRef

/**
 * 富文本（带图片的题干/选项/解析）。
 *
 * 约定格式（尽量贴近用户直觉，Excel 里直接粘贴即可）：
 *   - Markdown：`![图1](https://xxx/1.png)` 或 `![](./images/1.png)`
 *   - HTML：`<img src="https://xxx/1.png">`
 *   - 裸链接：一整段就是一个图片地址（.png/.jpg/.jpeg/.gif/.webp/.bmp/.svg）
 *
 * 图片地址可以是完整 URL，也可以是相对题库文件所在目录的路径（推荐：
 * Excel 与图片放在 alist 的同一个目录树下，换服务器也不用改表）。
 */
object RichText {

    sealed interface Block {
        data class Text(val text: String) : Block
        data class Image(val ref: String, val alt: String = "") : Block
    }

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif")

    private val MARKDOWN_IMAGE = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")
    private val HTML_IMAGE = Regex("<img[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)

    fun isImageRef(raw: String): Boolean {
        val s = raw.trim().substringBefore('?').substringBefore('#')
        val ext = s.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXT
    }

    /**
     * 把一段原始文本拆成「文本块 / 图片块」。
     * 解析不出来的内容一律当普通文本，绝不丢信息。
     */
    fun parse(raw: String): List<Block> {
        if (raw.isBlank()) return emptyList()
        val blocks = ArrayList<Block>()
        var cursor = 0
        val marks = ArrayList<Triple<Int, Int, Block.Image>>()

        MARKDOWN_IMAGE.findAll(raw).forEach { m ->
            marks.add(Triple(m.range.first, m.range.last + 1, Block.Image(m.groupValues[2].trim(), m.groupValues[1].trim())))
        }
        HTML_IMAGE.findAll(raw).forEach { m ->
            marks.add(Triple(m.range.first, m.range.last + 1, Block.Image(m.groupValues[1].trim(), "")))
        }
        // 纯文本行里只有一个图片地址的情况
        lineRanges(raw).forEach { (start, end) ->
            val line = raw.substring(start, end).trim()
            if (line.isNotEmpty() && !line.contains(' ') && isImageRef(line) && !line.contains("](")) {
                marks.add(Triple(start + raw.substring(start, end).indexOfFirst { !it.isWhitespace() }, end, Block.Image(line, "")))
            }
        }

        val sorted = marks.sortedWith(compareBy({ it.first }, { -(it.second - it.first) }))
        val accepted = ArrayList<Triple<Int, Int, Block.Image>>()
        var lastEnd = -1
        for (mark in sorted) {
            if (mark.first >= lastEnd) {
                accepted.add(mark)
                lastEnd = mark.second
            }
        }

        for ((start, end, image) in accepted) {
            if (start > cursor) {
                val text = raw.substring(cursor, start)
                if (text.isNotBlank()) blocks.add(Block.Text(text))
            }
            blocks.add(image)
            cursor = end
        }
        if (cursor < raw.length) {
            val text = raw.substring(cursor)
            if (text.isNotBlank()) blocks.add(Block.Text(text))
        }
        return blocks.ifEmpty { listOf(Block.Text(raw)) }
    }

    /** 抽出所有图片引用（用于预下载与本地缓存）。 */
    fun imageRefs(raw: String): List<String> =
        parse(raw).filterIsInstance<Block.Image>().map { it.ref }

    /**
     * 把图片引用补成「绝对地址或 alist 绝对路径」。
     *
     * - 完整 http(s) 地址：原样返回
     * - 相对路径：拼到题库文件所在目录下，例如 imageBase=/题库/网络 + ref=img/1.png
     *   → /题库/网络/img/1.png（交给 alist 的 fs/get 换直链，私有网盘也能看图）
     */
    fun toPath(ref: String, imageBase: String): String {
        val r = ref.trim()
        if (r.startsWith("http://", true) || r.startsWith("https://", true)) return r
        if (r.startsWith("//")) return "https:$r"
        if (r.startsWith("data:")) return r
        val dir = imageBase.trim().let { if (it.endsWith("/")) it.dropLast(1) else it }
        val path = when {
            r.startsWith("/") -> r
            dir.isEmpty() -> "/$r"
            else -> "$dir/$r"
        }
        return normalizePath(path)
    }

    /** 交给 AlistClient 下载用。 */
    fun toFileRef(ref: String, imageBase: String, baseUrl: String): FileRef =
        FileRef.parse(toPath(ref, imageBase), baseUrl)

    private fun normalizePath(path: String): String {
        val parts = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun lineRanges(text: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                out.add(start to i)
                start = i + 1
            }
            i++
        }
        out.add(start to text.length)
        return out
    }
}
