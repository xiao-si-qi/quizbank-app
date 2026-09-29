package com.xiaosiqi.quizbank.excel

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/**
 * 极薄的 SAX 封装。
 *
 * 选择 SAX 而不是 DOM 的原因：题库文件可能有上万道题，
 * DOM 会把整份 XML 载入内存（放大 10 倍以上），SAX 是流式的。
 * 选择 javax.xml.parsers 而不是 android.util.Xml 的原因：
 * 前者在 Android 与桌面 JVM 上都可用，解析逻辑可以直接写单元测试。
 */
internal object XmlSax {

    fun parse(bytes: ByteArray, handler: DefaultHandler) {
        ByteArrayInputStream(bytes).use { parse(it, handler) }
    }

    fun parse(stream: InputStream, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        // 防 XXE：不同平台支持的特性不同，逐个 try。
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        val parser = factory.newSAXParser()
        val reader = parser.xmlReader
        runCatching { reader.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.parse(InputSource(stream))
    }

    /** 去掉命名空间前缀，例如 "r:id" -> "id"。 */
    fun attr(attributes: Attributes?, name: String): String? {
        if (attributes == null) return null
        attributes.getValue(name)?.let { return it }
        val idx = name.indexOf(':')
        if (idx >= 0) {
            val local = name.substring(idx + 1)
            for (i in 0 until attributes.length) {
                val q = attributes.getQName(i)
                if (q == local || q.endsWith(":$local")) return attributes.getValue(i)
            }
        }
        return null
    }

    /** "B12" -> 1（0 基列号），非法返回 -1。 */
    fun columnOf(ref: String?): Int {
        if (ref.isNullOrEmpty()) return -1
        var col = 0
        var seen = false
        for (ch in ref) {
            when {
                ch in 'A'..'Z' -> { col = col * 26 + (ch - 'A' + 1); seen = true }
                ch in 'a'..'z' -> { col = col * 26 + (ch - 'a' + 1); seen = true }
                else -> return if (seen) col - 1 else -1
            }
        }
        return if (seen) col - 1 else -1
    }

    /** "B12" -> 11（0 基行号），非法返回 -1。 */
    fun rowOf(ref: String?): Int {
        if (ref.isNullOrEmpty()) return -1
        val digits = ref.takeLastWhile { it.isDigit() }
        return digits.toIntOrNull()?.minus(1) ?: -1
    }
}
