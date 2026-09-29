package com.xiaosiqi.quizbank.alist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRefTest {

    private val base = "https://pan.example.com"

    @Test
    fun `alist 路径原样变成 AlistPath`() {
        assertEquals(FileRef.AlistPath("/题库/网络.xlsx"), FileRef.parse("/题库/网络.xlsx", base))
        assertEquals(FileRef.AlistPath("/题库/网络.xlsx"), FileRef.parse("题库/网络.xlsx", base))
    }

    @Test
    fun `d 开头的直链保持为 Direct`() {
        val url = "$base/d/题库/网络.xlsx?sign=abc"
        assertEquals(FileRef.Direct(url), FileRef.parse(url, base))
    }

    @Test
    fun `浏览器地址栏的页面链接会转成 alist 路径`() {
        // 用户最常犯的错：复制的是网页地址而不是下载直链
        assertEquals(
            FileRef.AlistPath("/题库/网络.xlsx"),
            FileRef.parse("$base/题库/网络.xlsx", base),
        )
    }

    @Test
    fun `URL 编码的中文路径会被解码`() {
        assertEquals(
            FileRef.AlistPath("/题库/网络 基础.xlsx"),
            FileRef.parse("$base/%E9%A2%98%E5%BA%93/%E7%BD%91%E7%BB%9C%20%E5%9F%BA%E7%A1%80.xlsx", base),
        )
    }

    @Test
    fun `部署在子路径下的 alist 也能算对`() {
        val sub = "https://pan.example.com/alist"
        assertEquals(FileRef.AlistPath("/题库/网络.xlsx"), FileRef.parse("$sub/题库/网络.xlsx", sub))
        assertEquals(FileRef.Direct("$sub/d/题库/网络.xlsx"), FileRef.parse("$sub/d/题库/网络.xlsx", sub))
    }

    @Test
    fun `其他域名当普通直链`() {
        val other = "https://cdn.other.com/a.xlsx"
        assertEquals(FileRef.Direct(other), FileRef.parse(other, base))
    }

    @Test
    fun `目录计算：题库文件所在目录`() {
        assertEquals("/题库/网络", FileRef.directoryOf("/题库/网络/a.xlsx", base))
        assertEquals("/题库", FileRef.directoryOf("/题库/a.xlsx", base))
        assertEquals("/", FileRef.directoryOf("/a.xlsx", base))
        assertEquals("/题库/网络", FileRef.directoryOf("$base/d/题库/网络/a.xlsx", base))
        assertEquals("/题库/网络", FileRef.directoryOf("$base/题库/网络/a.xlsx", base))
        assertEquals("/题库/网络", FileRef.directoryOf("$base/d/%E9%A2%98%E5%BA%93/%E7%BD%91%E7%BB%9C/a.xlsx", base))
    }

    @Test
    fun `子路径部署时的目录计算不会带上前缀`() {
        val sub = "https://pan.example.com/alist"
        assertEquals("/题库/网络", FileRef.directoryOf("$sub/d/题库/网络/a.xlsx", sub))
    }

    @Test
    fun `路径归一化：去掉多余斜杠与点`() {
        assertEquals("/a/b", FileRef.normalizePath("a//b/"))
        assertEquals("/a/b", FileRef.normalizePath("/a/./b"))
        assertEquals("/b", FileRef.normalizePath("/a/../b"))
    }
}
