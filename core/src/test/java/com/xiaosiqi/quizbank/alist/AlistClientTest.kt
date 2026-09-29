package com.xiaosiqi.quizbank.alist

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder

class AlistClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(token: String = "tok") = AlistClient(server.url("/").toString().trimEnd('/'), token)

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun `登录拿到 token`() = runBlocking {
        server.enqueue(json("""{"code":200,"message":"success","data":{"token":"abc123"}}"""))
        val token = client("").login("小明", "密码")
        assertEquals("abc123", token)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/auth/login", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("小明"))
    }

    @Test
    fun `匿名访问不带 Authorization 头`() = runBlocking {
        server.enqueue(json("""{"code":200,"message":"success","data":{"content":[],"total":0}}"""))
        client("").list("/公开题库")
        val request = server.takeRequest()
        assertEquals(null, request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("/公开题库"))
    }

    @Test
    fun `带 token 时自动加上 Authorization 头`() = runBlocking {
        server.enqueue(json("""{"code":200,"message":"success","data":{"content":[],"total":0}}"""))
        client("mytoken").list("/")
        assertEquals("mytoken", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `目录列表按文件夹优先且自然排序`() = runBlocking {
        server.enqueue(
            json(
                """
                {"code":200,"message":"success","data":{"content":[
                  {"name":"第10章.xlsx","is_dir":false,"size":10},
                  {"name":"第2章.xlsx","is_dir":false,"size":10},
                  {"name":"图片","is_dir":true,"size":0}
                ],"total":3}}
                """.trimIndent()
            )
        )
        val items = client().list("/题库")
        assertEquals(listOf("图片", "第2章.xlsx", "第10章.xlsx"), items.map { it.name })
    }

    @Test
    fun `按 alist 路径下载：先 fs_get 再取直链`() = runBlocking {
        server.enqueue(
            json(
                """{"code":200,"message":"success","data":{"name":"a.xlsx","is_dir":false,"raw_url":"/d/题库/a.xlsx?sign=xyz"}}"""
            )
        )
        server.enqueue(MockResponse().setBody("FILE-BYTES"))

        val file = client().download(FileRef.AlistPath("/题库/a.xlsx"))
        assertEquals("FILE-BYTES", String(file.bytes))

        assertEquals("/api/fs/get", server.takeRequest().path)
        val second = server.takeRequest()
        // 非 ASCII 路径会被 OkHttp 百分号编码，这里只校验拼接与签名参数
        assertTrue("应当拼上服务器地址：${second.path}", second.path!!.startsWith("/d/"))
        assertTrue("应当带上 alist 给的签名：${second.path}", second.path!!.contains("sign=xyz"))
    }

    @Test
    fun `上传使用 URL 编码的 File-Path 头且是 PUT`() = runBlocking {
        server.enqueue(json("""{"code":200,"message":"success","data":null}"""))
        client().uploadText("/成绩/张三/20260101.json", """{"a":1}""")

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/fs/put", request.path)
        // 斜杠保留、非 ASCII 百分号编码、空格用 %20
        assertEquals("/%E6%88%90%E7%BB%A9/%E5%BC%A0%E4%B8%89/20260101.json", request.getHeader("File-Path"))
        assertEquals("tok", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"a\""))
    }

    @Test
    fun `新建目录`() = runBlocking {
        server.enqueue(json("""{"code":200,"message":"success","data":null}"""))
        client().mkdir("/成绩/张三")
        val request = server.takeRequest()
        assertEquals("/api/fs/mkdir", request.path)
        assertTrue(request.body.readUtf8().contains("/成绩/张三"))
    }

    @Test
    fun `me 支持数组形式的 role（新版权限模型）`() = runBlocking {
        server.enqueue(
            json(
                """{"code":200,"message":"success","data":{"id":1,"username":"admin","base_path":"/","role":[2],"permission":15}}"""
            )
        )
        val account = client().me()
        assertTrue(account.isAdmin)
        assertEquals("admin", account.username)
        assertEquals("管理员", account.roleLabel)
    }

    @Test
    fun `me 兼容数字形式的 role（旧版）`() = runBlocking {
        server.enqueue(
            json("""{"code":200,"message":"success","data":{"id":2,"username":"张三","base_path":"/成绩/张三","role":0}}""")
        )
        val account = client().me()
        assertFalse(account.isAdmin)
        assertEquals("普通用户", account.roleLabel)
    }

    @Test
    fun `401 会给出中文可操作提示`() {
        server.enqueue(json("""{"code":401,"message":"unauthorized","data":null}"""))
        val error = assertThrows(AlistException::class.java) {
            runBlocking { client().list("/私密目录") }
        }
        assertTrue("实际提示=${error.message}", error.message!!.contains("登录"))
        assertEquals(401, error.code)
    }

    @Test
    fun `返回网页时提示地址填错了`() {
        server.enqueue(MockResponse().setBody("<!DOCTYPE html><html><body>AList</body></html>"))
        val error = assertThrows(AlistException::class.java) {
            runBlocking { client().list("/") }
        }
        assertTrue(error.message!!.contains("服务器地址"))
    }

    @Test
    fun `下载到网页而不是文件时提示用直链`() {
        server.enqueue(MockResponse().setBody("<!DOCTYPE html><html></html>"))
        val error = assertThrows(AlistException::class.java) {
            runBlocking { client().fetchBytes(server.url("/题库/a.xlsx").toString()) }
        }
        assertTrue(error.message!!.contains("直链") || error.message!!.contains("网页"))
    }

    @Test
    fun `测试连接返回版本信息`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"title":"我的网盘","version":"v3.36.0"}"""))
        val info = client().settings()
        assertTrue(info.contains("AList v3.36.0"))
        assertTrue(info.contains("我的网盘"))
    }

    @Test
    fun `resolveRawUrl 支持绝对与相对地址`() {
        val c = AlistClient("https://pan.example.com", "")
        assertEquals("https://cdn.x.com/a.png", c.resolveRawUrl("https://cdn.x.com/a.png"))
        assertEquals("https://pan.example.com/d/a.png", c.resolveRawUrl("/d/a.png"))
    }

    // ------------------------------------------------------------ base_path 换算
    // alist 服务端的路径是 path.Join(base_path, req_path)，
    // 所以普通用户请求「绝对路径」时必须先把 base_path 去掉，否则会变成 /题库/题库/...

    @Test
    fun `账号根目录是斜杠时不改动路径`() {
        val c = AlistClient("https://x.com", "t", "/")
        assertEquals("/题库/内部/a.xlsx", c.requestPath("/题库/内部/a.xlsx"))
        assertEquals("/", c.requestPath("/"))
    }

    @Test
    fun `普通用户请求绝对路径时会去掉自己的根目录前缀`() {
        val c = AlistClient("https://x.com", "t", "/题库")
        assertEquals("/内部/a.xlsx", c.requestPath("/题库/内部/a.xlsx"))
        assertEquals("/成绩/student1/1.json", c.requestPath("/题库/成绩/student1/1.json"))
        assertEquals("/", c.requestPath("/题库"))
        // 不在自己根目录下的路径原样发出，交给服务端拒绝
        assertEquals("/文档/x.docx", c.requestPath("/文档/x.docx"))
    }

    @Test
    fun `匿名的根目录是公开目录时同样能换算`() {
        val c = AlistClient("https://x.com", "", "/题库/公开")
        assertEquals("/题库清单-公开.xlsx", c.requestPath("/题库/公开/题库清单-公开.xlsx"))
        assertEquals("/images/a.png", c.requestPath("/题库/公开/images/a.png"))
        assertEquals("/", c.requestPath("/题库/公开"))
    }

    @Test
    fun `列出目录时发送的是相对路径`() = runBlocking {
        server.enqueue(json("""{"code":200,"message":"success","data":{"content":[],"total":0}}"""))
        AlistClient(server.url("/").toString().trimEnd('/'), "tok", "/题库").list("/题库/内部")
        val body = server.takeRequest().body.readUtf8()
        assertTrue("实际请求体=$body", body.contains("\"path\":\"/内部\""))
    }

    @Test
    fun `上传时 File-Path 头是相对路径`() = runBlocking {
        server.enqueue(json("""{"code":200,"message":"success","data":null}"""))
        AlistClient(server.url("/").toString().trimEnd('/'), "tok", "/题库")
            .uploadText("/题库/成绩/student1/2026.json", "{}")
        val request = server.takeRequest()
        // 解码后应当正好是「去掉账号根目录」的相对路径
        assertEquals("/成绩/student1/2026.json", URLDecoder.decode(request.getHeader("File-Path"), "UTF-8"))
    }

    @Test
    fun `withBasePath 只换根目录不换服务器与 token`() {
        val c = AlistClient("https://x.com", "tok", "/")
        val c2 = c.withBasePath("/题库")
        assertEquals("https://x.com", c2.baseUrl)
        assertEquals("tok", c2.token)
        assertEquals("/题库", c2.basePath)
    }

    // ------------------------------------------------- 真实 alist 的返回形状
    // 下面这段 JSON 是从真实 alist 上抓下来的：header 是「空字符串」而不是对象，
    // 早先按 Map<String,String> 建模时，每一次文件下载都会解析失败。

    @Test
    fun `真实 alist 的 fs_get 响应能解析并下载`() = runBlocking {
        val raw = server.url("/p/x.xlsx?sign=abc").toString()
        server.enqueue(
            json(
                """{"code":200,"message":"success","data":{"name":"计算机基础.xlsx","size":4291,""" +
                    """"is_dir":false,"modified":"2026-09-28T20:09:06.077324744+08:00",""" +
                    """"created":"2026-09-28T20:09:06.072832553+08:00","sign":"ereuJTCMdiqU-RjMfGi72vzfSTmcVG-gtnk8OiSm4mE=:0",""" +
                    """"thumb":"","type":0,"hashinfo":"null","hash_info":null,"raw_url":"$raw",""" +
                    """"readme":"","header":"","provider":"Local","related":null}}"""
            )
        )
        server.enqueue(MockResponse().setBody("FILE-BYTES"))
        val file = client().download(FileRef.AlistPath("/题库/公开/计算机基础.xlsx"))
        assertEquals("FILE-BYTES", String(file.bytes))
    }

    @Test
    fun `header 字段的三种形态都能解析成请求头`() {
        val c = AlistClient("https://x.com", "t")
        assertEquals(emptyMap<String, String>(), c.parseHeader(null))
        assertEquals(emptyMap<String, String>(), c.parseHeader(JsonPrimitive("")))
        assertEquals(emptyMap<String, String>(), c.parseHeader(JsonPrimitive("null")))
        // 字符串里包着 JSON（部分驱动会这样返回）
        assertEquals(
            mapOf("Referer" to "https://pan.example.com"),
            c.parseHeader(JsonPrimitive("""{"Referer":"https://pan.example.com"}""")),
        )
        // 直接就是对象
        assertEquals(mapOf("X-Auth" to "1"), c.parseHeader(buildJsonObject { put("X-Auth", "1") }))
    }
}
