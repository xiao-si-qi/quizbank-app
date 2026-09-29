package com.xiaosiqi.quizbank.importer

/** 文本归一化工具：中文题库文件里全角/半角、空格、标点混用非常普遍。 */
object TextNorm {

    private val FULL_TO_HALF: CharArray = CharArray(0x10000) { i ->
        when {
            i == 0x3000 -> ' '
            i in 0xFF01..0xFF5E -> (i - 0xFEE0).toChar()
            else -> i.toChar()
        }
    }

    /** 全角转半角。 */
    fun toHalfWidth(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(if (ch.code < FULL_TO_HALF.size) FULL_TO_HALF[ch.code] else ch)
        return sb.toString()
    }

    /** 用于表头匹配：去空白、去括号内容、去标点、小写。 */
    fun header(raw: String): String {
        var s = toHalfWidth(raw).lowercase()
        s = s.replace(Regex("[\\(（\\[【][^\\)）\\]】]*[\\)）\\]】]"), "")
        s = s.replace(Regex("[\\s\\p{Punct}、。，：；！？·]"), "")
        return s
    }

    /** 单元格取值：去首尾空白、压缩内部空白，但保留换行（题干可能多行）。 */
    fun value(raw: String): String {
        if (raw.isEmpty()) return ""
        // 不做全角转半角：题干里的「？」「（）」属于原文，转换会改变用户看到的内容
        val normalized = raw.replace('\u00A0', ' ')
        val lines = normalized.split('\n').map { it.trim().replace(Regex("[ \\t]{2,}"), " ") }
        return lines.joinToString("\n").trim()
    }

    /** 单行紧凑化：所有空白折叠成一个空格，用于选项文本。 */
    /**
     * 显示用的名字：把百分号编码解回中文。
     *
     * 题库清单里如果没写「题库名称」，我们会从文件地址推名字；
     * 而地址常常是 `.../%E4%BF%A1%E5%8F%B7%E5%B7%A5.xlsx` 这种带百分号编码的形式，
     * 直接取最后一段就会显示成乱码，所以这里统一解一次。
     * 只有看起来确实是编码过的才解，正常中文名原样返回。
     */
    fun displayName(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return text
        if (!Regex("%[0-9A-Fa-f]{2}").containsMatchIn(text)) return text
        return runCatching {
            // '+' 在文件名里是普通字符，先保护起来再解码
            java.net.URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(text)
    }

    fun oneLine(raw: String): String = value(raw).replace(Regex("\\s+"), " ").trim()

    /** 宽松比较用：去掉全部空白与常见分隔标点后小写。 */
    fun loose(raw: String): String =
        toHalfWidth(raw).lowercase().replace(Regex("[\\s\\p{Punct}、。，：；！？·“”‘’]"), "")

    fun toIntOrNull(raw: String): Int? {
        val s = toHalfWidth(raw).trim()
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }
        val d = s.toDoubleOrNull() ?: return Regex("-?\\d+").find(s)?.value?.toIntOrNull()
        return d.toInt()
    }

    fun toDoubleOrNull(raw: String): Double? {
        val s = toHalfWidth(raw).trim().removeSuffix("分")
        if (s.isEmpty()) return null
        return s.toDoubleOrNull() ?: Regex("-?\\d+(\\.\\d+)?").find(s)?.value?.toDoubleOrNull()
    }

    /** 拆分标签/多答案。 */
    fun splitList(raw: String): List<String> =
        raw.split(Regex("[,，、;；|/\\n\\t]+")).map { it.trim() }.filter { it.isNotEmpty() }
}
