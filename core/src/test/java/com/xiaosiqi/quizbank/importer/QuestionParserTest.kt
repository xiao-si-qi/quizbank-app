package com.xiaosiqi.quizbank.importer

import com.xiaosiqi.quizbank.excel.SheetTable
import com.xiaosiqi.quizbank.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionParserTest {

    private fun sheet(vararg rows: List<String>, name: String = "题目") = SheetTable(name, rows.toList())

    @Test
    fun `标准表头能解析出五种题型`() {
        val table = sheet(
            listOf("题号", "题型", "题干", "选项A", "选项B", "选项C", "选项D", "答案", "解析"),
            listOf("1", "单选题", "首都是？", "北京", "上海", "广州", "深圳", "A", "常识"),
            listOf("2", "多选题", "哪些是输入设备？", "键盘", "鼠标", "打印机", "扫描仪", "ABD", "打印机是输出"),
            listOf("3", "判断题", "地球是圆的", "", "", "", "", "对", ""),
            listOf("4", "填空题", "水的化学式是____", "", "", "", "", "H2O", ""),
            listOf("5", "简答题", "简述光合作用", "", "", "", "", "把光能转化为化学能", ""),
        )
        val result = QuestionParser.parseWorkbook(listOf(table))
        assertEquals(5, result.count)
        assertEquals(QuestionType.SINGLE, result.questions[0].type)
        assertEquals(QuestionType.MULTIPLE, result.questions[1].type)
        assertEquals(QuestionType.JUDGE, result.questions[2].type)
        assertEquals(QuestionType.BLANK, result.questions[3].type)
        assertEquals(QuestionType.ESSAY, result.questions[4].type)
        assertEquals(listOf("A"), result.questions[0].answers)
        assertEquals(listOf("A", "B", "D"), result.questions[1].answers)
        assertEquals(listOf("把光能转化为化学能"), result.questions[4].answers)
    }

    @Test
    fun `判断题自动补上正确错误两个选项`() {
        val table = sheet(
            listOf("题干", "答案"),
            listOf("天空是蓝色的", "对"),
            listOf("太阳从西边升起", "错"),
        )
        val result = QuestionParser.parseWorkbook(listOf(table))
        assertEquals(2, result.count)
        val first = result.questions[0]
        assertEquals(QuestionType.JUDGE, first.type)
        assertEquals(listOf("A", "B"), first.options.map { it.key })
        assertEquals("A", first.answers.first())
        assertEquals("B", result.questions[1].answers.first())
    }

    @Test
    fun `表头别名与顺序都能容错`() {
        // 列顺序打乱、表头用不同叫法、前面还有无关列
        val table = sheet(
            listOf("备注", "正确答案", "题目", "A", "B", "C", "解析内容"),
            listOf("随便写点", "B", "HTTP 默认端口是？", "21", "80", "443", "80 是 HTTP"),
        )
        val result = QuestionParser.parseWorkbook(listOf(table))
        assertEquals(1, result.count)
        val q = result.questions[0]
        assertEquals("HTTP 默认端口是？", q.stem)
        assertEquals(3, q.options.size)
        assertEquals("B", q.answers.first())
        assertEquals("80 是 HTTP", q.analysis)
    }

    @Test
    fun `题干里内嵌的选项会被拆开`() {
        val table = sheet(
            listOf("题干", "答案"),
            listOf("下列正确的是\nA. 甲说法\nB. 乙说法\nC. 丙说法\nD. 丁说法", "C"),
        )
        val result = QuestionParser.parseWorkbook(listOf(table))
        val q = result.questions[0]
        assertEquals("下列正确的是", q.stem)
        assertEquals(4, q.options.size)
        assertEquals("甲说法", q.options[0].text)
        assertEquals("C", q.answers.first())
    }

    @Test
    fun `选项挤在一格时按 A点B点 拆开`() {
        val table = sheet(
            listOf("题干", "选项", "答案"),
            listOf("1+1=?", "A. 1  B. 2  C. 3", "B"),
        )
        val q = QuestionParser.parseWorkbook(listOf(table)).questions[0]
        assertEquals(3, q.options.size)
        assertEquals("2", q.options[1].text)
    }

    @Test
    fun `答案写成选项文本也能对上`() {
        val table = sheet(
            listOf("题干", "选项A", "选项B", "答案"),
            listOf("地球的卫星是？", "月球", "太阳", "月球"),
        )
        val q = QuestionParser.parseWorkbook(listOf(table)).questions[0]
        assertEquals(listOf("A"), q.answers)
    }

    @Test
    fun `多种答案写法：对错 勾叉 T F 是真假`() {
        val table = sheet(
            listOf("题干", "答案"),
            listOf("题1", "√"),
            listOf("题2", "×"),
            listOf("题3", "T"),
            listOf("题4", "F"),
            listOf("题5", "正确"),
            listOf("题6", "错误"),
        )
        val result = QuestionParser.parseWorkbook(listOf(table))
        assertEquals(listOf("A"), result.questions[0].answers)
        assertEquals(listOf("B"), result.questions[1].answers)
        assertEquals(listOf("A"), result.questions[2].answers)
        assertEquals(listOf("B"), result.questions[3].answers)
        assertEquals(listOf("A"), result.questions[4].answers)
        assertEquals(listOf("B"), result.questions[5].answers)
    }

    @Test
    fun `多选答案的分隔符随便写`() {
        listOf("ABD", "A,B,D", "A、B、D", "A B D", "A；B；D").forEach { raw ->
            val table = sheet(
                listOf("题干", "选项A", "选项B", "选项C", "选项D", "答案"),
                listOf("哪些是水果？", "苹果", "香蕉", "白菜", "土豆", raw),
            )
            val q = QuestionParser.parseWorkbook(listOf(table)).questions[0]
            assertEquals("答案写法=$raw", QuestionType.MULTIPLE, q.type)
            assertEquals("答案写法=$raw", listOf("A", "B", "D"), q.answers)
        }
    }

    @Test
    fun `填空题的多个空用竖线分隔`() {
        val table = sheet(
            listOf("题干", "答案"),
            listOf("____ 的首都是 ____", "中国|北京"),
        )
        val q = QuestionParser.parseWorkbook(listOf(table)).questions[0]
        assertEquals(QuestionType.BLANK, q.type)
        assertEquals(listOf("中国", "北京"), q.answers)
    }

    @Test
    fun `题型写在题干前缀里也能识别并去掉前缀`() {
        val table = sheet(
            listOf("题干", "选项A", "选项B", "答案"),
            listOf("【单选题】下面哪个是编程语言？", "Python", "HTML", "A"),
        )
        val q = QuestionParser.parseWorkbook(listOf(table)).questions[0]
        assertEquals(QuestionType.SINGLE, q.type)
        assertEquals("下面哪个是编程语言？", q.stem)
    }

    @Test
    fun `没有题型列时按选项与答案推断`() {
        val single = QuestionParser.parseWorkbook(
            listOf(
                sheet(
                    listOf("题干", "选项A", "选项B", "答案"),
                    listOf("1+1=?", "1", "2", "B"),
                )
            )
        ).questions[0]
        assertEquals(QuestionType.SINGLE, single.type)

        val multiple = QuestionParser.parseWorkbook(
            listOf(
                sheet(
                    listOf("题干", "选项A", "选项B", "选项C", "答案"),
                    listOf("选两个", "甲", "乙", "丙", "AC"),
                )
            )
        ).questions[0]
        assertEquals(QuestionType.MULTIPLE, multiple.type)

        val blank = QuestionParser.parseWorkbook(
            listOf(sheet(listOf("题干", "答案"), listOf("首都是____", "北京")))
        ).questions[0]
        assertEquals(QuestionType.BLANK, blank.type)

        val essay = QuestionParser.parseWorkbook(
            listOf(sheet(listOf("题干", "答案"), listOf("请论述这个问题并给出你的看法", "参考答案：言之有理即可")))
        ).questions[0]
        assertEquals(QuestionType.ESSAY, essay.type)
    }

    @Test
    fun `多个工作表会合并导入，工作表名可当题型`() {
        val single = sheet(
            listOf("题干", "选项A", "选项B", "答案"),
            listOf("单选一题", "甲", "乙", "A"),
            name = "单选题",
        )
        val judge = sheet(listOf("题干", "答案"), listOf("判断一题", "对"), name = "判断题")
        val result = QuestionParser.parseWorkbook(listOf(single, judge))
        assertEquals(2, result.count)
        assertEquals(QuestionType.SINGLE, result.questions[0].type)
        assertEquals(QuestionType.JUDGE, result.questions[1].type)
    }

    @Test
    fun `空题干的行会被跳过并计数`() {
        val table = sheet(
            listOf("题干", "答案"),
            listOf("正常题", "A"),
            listOf("", ""),
            listOf("   ", "B"),
            listOf("第二题", "B"),
        )
        val result = QuestionParser.parseWorkbook(listOf(table))
        assertEquals(2, result.count)
        assertTrue(result.skipped >= 1)
    }

    @Test
    fun `完全读不出题目时给出可操作的提示`() {
        val table = sheet(listOf("姓名", "电话"), listOf("张三", "123"))
        val error = runCatching { QuestionParser.parseWorkbook(listOf(table)) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is ImportException)
        assertTrue(error!!.message!!.contains("题干"))
    }

    @Test
    fun `难度与分值会被解析`() {
        val table = sheet(
            listOf("题干", "答案", "难度", "分值", "章节", "标签"),
            listOf("某题", "A", "3", "2.5", "第一章", "重点,易错"),
            listOf("一级难度", "A", "1", "1", "", ""),
            listOf("百分比难度", "A", "0.4", "1", "", ""),
            listOf("没填难度", "A", "", "1", "", ""),
        )
        val result = QuestionParser.parseWorkbook(listOf(table))
        val q = result.questions[0]
        assertEquals(3, q.difficulty)
        assertEquals(2.5, q.score, 0.001)
        assertEquals("第一章", q.chapter)
        assertEquals(listOf("重点", "易错"), q.tags)
        // 难度填 1 是「1 级」，不是 100%（曾经被换算成 5 星）
        assertEquals(1, result.questions[1].difficulty)
        // 小数才按比例换算到 1-5 档
        assertEquals(2, result.questions[2].difficulty)
        assertEquals(0, result.questions[3].difficulty)
    }

    @Test
    fun `题型列只写选择题时按答案字母个数分单选多选`() {
        // 很多 Word/纸质题库转出来的表头只写「选择题」，不分单多选
        val header = listOf("题干", "选项A", "选项B", "选项C", "选项D", "题型", "答案")
        val rows = listOf(
            header,
            listOf("下列哪种垃圾属于有害垃圾？", "果皮", "废电池", "纸箱", "塑料瓶", "选择题", "B"),
            listOf("下列属于可再生能源的有？", "太阳能", "风能", "煤炭", "石油", "选择题", "AB"),
            listOf("既有单选题又有判断题时？", "甲", "乙", "丙", "丁", "选择题", "ABCD"),
        )
        val questions = QuestionParser.parseWorkbook(listOf(SheetTable("Sheet1", rows))).questions

        assertEquals("答案是单个字母 → 单选", QuestionType.SINGLE, questions[0].type)
        assertEquals(listOf("B"), questions[0].answers)
        assertEquals("答案是两个字母 → 多选", QuestionType.MULTIPLE, questions[1].type)
        assertEquals(listOf("A", "B"), questions[1].answers)
        assertEquals(QuestionType.MULTIPLE, questions[2].type)
        assertEquals(listOf("A", "B", "C", "D"), questions[2].answers)
        // 选项和题干不能被弄坏
        assertEquals(4, questions[0].options.size)
        assertEquals("废电池", questions[0].options[1].text)
        assertEquals("下列哪种垃圾属于有害垃圾？", questions[0].stem)
    }

    @Test
    fun `显式写单选题时不会因为答案多个字母就变成多选`() {
        val header = listOf("题型", "题干", "选项A", "选项B", "选项C", "答案")
        val rows = listOf(header, listOf("单选题", "某题", "甲", "乙", "丙", "AC"))
        val q = QuestionParser.parseWorkbook(listOf(SheetTable("Sheet1", rows))).questions.single()
        // 单选题配了两个字母属于数据矛盾，按多选处理更合理（否则答案挂不上）
        assertEquals(listOf("A", "C"), q.answers)
        assertTrue(q.answers.all { it in listOf("A", "B", "C") })
    }
}
