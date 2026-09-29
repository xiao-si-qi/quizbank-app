package com.xiaosiqi.quizbank.importer

import com.xiaosiqi.quizbank.excel.XlsxWriter
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 生成用于灌进 alist 的测试题库（公开 / 内部各一份 + 目录表 + 一张图片）。
 * 产物写在 core/build/alist-fixtures/ 下，方便直接上传。
 */
class AlistFixtureTest {

    private val out = File("build/alist-fixtures")

    @Test
    fun `生成公开与内部测试题库`() {
        out.mkdirs()

        write("公开/计算机基础.xlsx", computerBank())
        write("公开/生活常识.xlsx", lifeBank())
        write("内部/消防安全-内部.xlsx", fireBank())
        write("题库清单-公开.xlsx", publicList())
        write("题库清单-全部.xlsx", fullList())

        // 图片：供题干引用，验证「Excel 写图片链接 → App 从 alist 取图并缓存」整条链路
        File(out, "公开/images").mkdirs()
        File(out, "公开/images/http-flow.png").writeBytes(samplePng())

        assertTrue(File(out, "公开/计算机基础.xlsx").length() > 2000)
        assertTrue(File(out, "内部/消防安全-内部.xlsx").length() > 2000)
        assertTrue(File(out, "公开/images/http-flow.png").length() > 200)
    }

    private fun write(relative: String, bytes: ByteArray) {
        val file = File(out, relative)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private val headers = listOf(
        "题号", "题型", "题干", "选项A", "选项B", "选项C", "选项D",
        "答案", "解析", "难度", "章节", "标签", "分值",
    )

    private fun bank(sheetName: String, rows: List<List<Any?>>) = XlsxWriter.write(
        listOf(
            XlsxWriter.Sheet(sheetName, listOf(headers) + rows, listOf(6, 9, 52, 18, 18, 18, 18, 10, 40, 6, 14, 14, 6)),
        )
    )

    private fun computerBank() = bank(
        "题目",
        listOf(
            listOf("1", "单选题", "HTTP 协议默认使用的端口是？", "21", "80", "443", "8080", "B", "HTTP 默认 80，HTTPS 默认 443。", 1, "网络基础", "公开,网络", 1),
            listOf("2", "单选题", "下图描述的是 TCP 建立连接的过程，请问这是几次握手？\n![三次握手示意图](images/http-flow.png)", "一次", "两次", "三次", "四次", "C", "TCP 建立连接需要三次握手。", 2, "网络基础", "公开,重点", 1),
            listOf("3", "多选题", "下列属于操作系统的是？", "Windows", "Linux", "MySQL", "macOS", "ABD", "MySQL 是数据库，不是操作系统。", 2, "操作系统", "公开", 2),
            listOf("4", "多选题", "下列关于 IP 地址的说法正确的有？", "IPv4 是 32 位", "IPv6 是 128 位", "IPv4 地址全球唯一且永久不变", "127.0.0.1 是本机回环地址", "ABD", "IPv4 地址会因网络变化而改变，不是永久不变。", 3, "网络基础", "公开,易错", 2),
            listOf("5", "判断题", "1 GB 等于 1024 MB。", "", "", "", "", "对", "计算机中按 1024 进制换算。", 1, "计算机基础", "公开", 1),
            listOf("6", "判断题", "在 Windows 中删除文件后，文件一定无法恢复。", "", "", "", "", "错", "回收站中的文件可以还原。", 2, "计算机基础", "公开", 1),
            listOf("7", "判断题", "CPU 的运算速度只由主频决定，与核心数无关。", "", "", "", "", "错", "核心数、缓存、架构都会影响性能。", 3, "硬件", "公开", 1),
            listOf("8", "填空题", "计算机中，1 字节等于 ______ 位。", "", "", "", "", "8", "1 Byte = 8 bit。", 1, "计算机基础", "公开", 1),
            listOf("9", "填空题", "在 Excel 中，求 A1 到 A10 之和的函数写法是 ______。", "", "", "", "", "SUM(A1:A10)", "常用求和函数。", 2, "办公软件", "公开", 1),
            listOf("10", "填空题", "十进制数 10 转换为二进制是 ______。", "", "", "", "", "1010", "10 = 8 + 2 = 1010₂。", 2, "计算机基础", "公开", 1),
            listOf("11", "简答题", "请简述什么是「死锁」，以及产生死锁的四个必要条件。", "", "", "", "", "死锁是指两个或多个进程在执行过程中，因争夺资源而造成的一种互相等待的现象。四个必要条件：互斥、请求与保持、不可剥夺、循环等待。", "答出四个必要条件即可。", 4, "操作系统", "公开,主观题", 5),
            listOf("12", "简答题", "请说明为什么建议定期备份重要数据。", "", "", "", "", "硬件故障、误删除、病毒勒索、自然灾害等都可能造成数据丢失；备份是唯一可靠的补救手段，且备份要遵循「三份副本、两种介质、一份异地」。", "言之有理即可。", 2, "计算机基础", "公开", 5),
        ),
    )

    private fun lifeBank() = bank(
        "题目",
        listOf(
            listOf("1", "单选题", "一年中白昼最长的节气是？", "春分", "夏至", "秋分", "冬至", "B", "夏至日北半球白昼最长。", 1, "自然常识", "公开", 1),
            listOf("2", "单选题", "下列哪种垃圾属于有害垃圾？", "果皮", "废电池", "纸箱", "塑料瓶", "B", "废电池含重金属，属于有害垃圾。", 1, "生活常识", "公开", 1),
            listOf("3", "多选题", "下列属于可再生能源的有？", "太阳能", "风能", "煤炭", "地热能", "ABD", "煤炭属于化石能源，不可再生。", 2, "能源常识", "公开", 2),
            listOf("4", "判断题", "油锅起火时应该立刻用水浇灭。", "", "", "", "", "错", "水会让油飞溅导致火势扩大，应盖锅盖隔绝空气。", 2, "安全常识", "公开,易错", 1),
            listOf("5", "判断题", "冰箱冷藏室适合存放所有种类的食物。", "", "", "", "", "错", "部分水果、热带作物不宜冷藏。", 3, "生活常识", "公开", 1),
            listOf("6", "填空题", "成年人正常体温大约是 ______ 摄氏度。", "", "", "", "", "37", "腋下正常体温约 36–37℃。", 1, "健康常识", "公开", 1),
            listOf("7", "填空题", "中国的首都是 ______，最大的城市是 ______。", "", "", "", "", "北京|上海", "两个空用竖线分隔。", 1, "地理常识", "公开", 1),
            listOf("8", "简答题", "请简述遇到地震时在室内应该怎么做。", "", "", "", "", "保持冷静，就近躲避到坚固的桌子下或承重墙角落，护住头部；远离窗户和悬挂物；不要乘坐电梯；震动停止后迅速有序撤离到空旷地带。", "答出躲避与撤离要点即可。", 2, "安全常识", "公开,主观题", 5),
        ),
    )

    private fun fireBank() = bank(
        "题目",
        listOf(
            listOf("1", "单选题", "我国消防工作的方针是？", "预防为主、防消结合", "安全第一、预防为主", "谁主管、谁负责", "综合治理", "A", "《消防法》规定：预防为主、防消结合。", 2, "消防基础", "内部", 1),
            listOf("2", "单选题", "灭火器上的压力表指针指向哪个区域表示压力正常？", "红区", "绿区", "黄区", "任意区域", "B", "绿区为正常，红区为压力不足，黄区为超压。", 2, "消防器材", "内部,重点", 1),
            listOf("3", "多选题", "发生火灾时，下列做法正确的有？", "用湿毛巾捂住口鼻低姿撤离", "乘坐电梯快速下楼", "拨打 119 报警", "盲目跳楼逃生", "AC", "火灾时严禁乘坐电梯和跳楼。", 2, "火灾逃生", "内部,易错", 2),
            listOf("4", "多选题", "下列属于消防设施的有？", "消火栓", "火灾自动报警系统", "应急照明灯", "空调外机", "ABC", "空调外机不属于消防设施。", 2, "消防设施", "内部", 2),
            listOf("5", "判断题", "任何人发现火灾都应当立即报警。", "", "", "", "", "对", "《消防法》规定任何单位、个人都有报告火警的义务。", 1, "消防法规", "内部", 1),
            listOf("6", "判断题", "安全出口的疏散门在营业期间可以上锁。", "", "", "", "", "错", "严禁锁闭、堵塞安全出口。", 1, "消防法规", "内部,易错", 1),
            listOf("7", "判断题", "灭火时应该把灭火器喷嘴对准火焰的根部。", "", "", "", "", "对", "对准根部才能有效切断燃烧。", 2, "消防器材", "内部", 1),
            listOf("8", "填空题", "我国的火警电话是 ______。", "", "", "", "", "119", "火警 119，匪警 110，急救 120。", 1, "消防基础", "内部", 1),
            listOf("9", "填空题", "灭火器使用口诀「提、拔、握、压」中的「拔」指的是拔掉 ______。", "", "", "", "", "保险销", "拔掉保险销才能喷射。", 2, "消防器材", "内部", 1),
            listOf("10", "简答题", "请简述火灾发生时组织人员疏散的基本原则。", "", "", "", "", "先救人后救物；按疏散指示标志就近有序撤离，避免拥挤踩踏；优先疏散老弱病残；明确分工，指定集合点并清点人数；严禁使用电梯。", "答出「先救人」「有序」「清点人数」等要点即可。", 3, "火灾逃生", "内部,主观题", 5),
        ),
    )

    private fun listHeaders() = listOf("题库ID", "题库名称", "分类", "描述", "文件地址", "题目数量", "版本", "标签")

    private fun publicList() = XlsxWriter.write(
        listOf(
            XlsxWriter.Sheet(
                TemplateFactory.SHEET_LIST,
                listOf(listHeaders()) + listOf(
                    listOf("pub-cs", "计算机基础", "计算机", "公开题库，免登录即可练习", "/题库/公开/计算机基础.xlsx", 12, "v1", "公开,入门"),
                    listOf("pub-life", "生活常识", "常识", "公开题库，含生活与安全常识", "/题库/公开/生活常识.xlsx", 8, "v1", "公开"),
                ),
                listOf(10, 22, 10, 34, 40, 10, 8, 14),
            ),
            XlsxWriter.Sheet(
                TemplateFactory.SHEET_HELP,
                listOf(
                    "【公开题库清单】",
                    "",
                    "这份清单放在 /题库/公开/ 下，匿名（不登录）也能读取，",
                    "所以 App 不填账号密码就能同步到这两个题库。",
                    "",
                    "想加题目：在 /题库/公开/ 下新建 xlsx，格式见「题库模板」，",
                    "然后在这里加一行指向它即可。",
                ).map { listOf<Any?>(it) },
                listOf(80),
                boldHeader = false,
            ),
        ),
    )

    private fun fullList() = XlsxWriter.write(
        listOf(
            XlsxWriter.Sheet(
                TemplateFactory.SHEET_LIST,
                listOf(listHeaders()) + listOf(
                    listOf("pub-cs", "计算机基础", "计算机", "公开题库，免登录即可练习", "/题库/公开/计算机基础.xlsx", 12, "v1", "公开,入门"),
                    listOf("pub-life", "生活常识", "常识", "公开题库，含生活与安全常识", "/题库/公开/生活常识.xlsx", 8, "v1", "公开"),
                    listOf("in-fire", "消防安全（内部）", "资格证", "需要登录 alist 才能看到", "/题库/内部/消防安全-内部.xlsx", 10, "v1", "内部,考证"),
                ),
                listOf(10, 22, 10, 34, 40, 10, 8, 14),
            ),
            XlsxWriter.Sheet(
                TemplateFactory.SHEET_HELP,
                listOf(
                    "【完整题库清单】",
                    "",
                    "这份清单在 /题库/ 下，只有登录后（且有权限）才能读取，",
                    "里面同时包含公开题库和内部题库。",
                ).map { listOf<Any?>(it) },
                listOf(80),
                boldHeader = false,
            ),
        ),
    )

    // ---------------------------------------------------------------- PNG

    /** 手写一张 PNG（不依赖任何图形库），用来测试图片链路。 */
    private fun samplePng(width: Int = 240, height: Int = 120): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()))
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            out.write(typeBytes)
            out.write(data)
            val crc = CRC32().apply { update(typeBytes); update(data) }
            val v = crc.value
            out.write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
            return out.toByteArray()
        }

        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0) // filter: none
            for (x in 0 until width) {
                // 画一个简单的渐变 + 白色方框，肉眼可辨认
                val border = x in 20..(width - 20) && y in 20..(height - 20) && (x < 24 || x > width - 24 || y < 24 || y > height - 24)
                val r = if (border) 255 else (40 + 180 * x / width)
                val g = if (border) 255 else (90 + 120 * y / height)
                val b = if (border) 255 else 220
                raw.write(r); raw.write(g); raw.write(b)
            }
        }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            compressed.write(buf, 0, n)
        }
        deflater.end()

        val ihdr = ByteArrayOutputStream().apply {
            write(byteArrayOf((width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte()))
            write(byteArrayOf((height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte()))
            write(byteArrayOf(8, 2, 0, 0, 0)) // 8bit, truecolor, no interlace
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            write(chunk("IHDR", ihdr))
            write(chunk("IDAT", compressed.toByteArray()))
            write(chunk("IEND", ByteArray(0)))
        }.toByteArray()
    }
}
