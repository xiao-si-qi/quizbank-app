package com.xiaosiqi.quizbank.rich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextTest {

    @Test
    fun `马克达图片语法能解析成图片块`() {
        val blocks = RichText.parse("看下图 ![示意图](https://x.com/a.png) 然后作答")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is RichText.Block.Text)
        assertEquals("https://x.com/a.png", (blocks[1] as RichText.Block.Image).ref)
        assertEquals("示意图", blocks[1].let { (it as RichText.Block.Image).alt })
        assertTrue(blocks[2] is RichText.Block.Text)
    }

    @Test
    fun `HTML img 标签也能解析`() {
        val blocks = RichText.parse("<img src='img/1.png' />文字在后")
        assertEquals(2, blocks.size)
        assertEquals("img/1.png", (blocks[0] as RichText.Block.Image).ref)
    }

    @Test
    fun `单独一行的图片地址会被当成图片`() {
        val blocks = RichText.parse("请参考下图：\nhttps://x.com/b.jpg")
        val images = blocks.filterIsInstance<RichText.Block.Image>()
        assertEquals(1, images.size)
        assertEquals("https://x.com/b.jpg", images[0].ref)
    }

    @Test
    fun `普通文字不会被误判成图片`() {
        val blocks = RichText.parse("这道题考的是 file.txt 的读取方式，注意 A. 选项")
        assertTrue(blocks.all { it is RichText.Block.Text })
    }

    @Test
    fun `抽出所有图片引用`() {
        val refs = RichText.imageRefs("![a](1.png) 和 ![b](2.jpg) 以及 <img src='3.gif'>")
        assertEquals(listOf("1.png", "2.jpg", "3.gif"), refs)
    }

    @Test
    fun `相对路径会拼到题库目录下`() {
        assertEquals("/题库/网络/img/1.png", RichText.toPath("img/1.png", "/题库/网络"))
        assertEquals("/题库/网络/1.png", RichText.toPath("./1.png", "/题库/网络/"))
        assertEquals("/题库/1.png", RichText.toPath("../1.png", "/题库/网络"))
        assertEquals("/其他/1.png", RichText.toPath("/其他/1.png", "/题库/网络"))
    }

    @Test
    fun `完整网址不受目录影响`() {
        assertEquals("https://cdn.x.com/1.png", RichText.toPath("https://cdn.x.com/1.png", "/题库"))
        assertEquals("https://cdn.x.com/1.png", RichText.toPath("//cdn.x.com/1.png", "/题库"))
    }

    @Test
    fun `相对路径会转成 alist 路径以便带权限下载`() {
        val ref = RichText.toFileRef("img/1.png", "/题库/网络", "https://pan.example.com")
        assertEquals(FileRefExpectation, ref)
    }

    private val FileRefExpectation = com.xiaosiqi.quizbank.alist.FileRef.AlistPath("/题库/网络/img/1.png")

    @Test
    fun `空文本不会炸`() {
        assertTrue(RichText.parse("").isEmpty())
        assertTrue(RichText.parse("   ").isEmpty())
        assertEquals(emptyList<String>(), RichText.imageRefs(""))
    }
}
