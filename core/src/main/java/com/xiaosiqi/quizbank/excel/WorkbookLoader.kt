package com.xiaosiqi.quizbank.excel

/** 统一的表格载入入口：按文件魔数判断格式，而不是只信扩展名。 */
object WorkbookLoader {

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B)
    private val OLE2_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())

    fun load(bytes: ByteArray, fileName: String = ""): List<SheetTable> {
        if (bytes.size < 4) throw ExcelFormatException("文件内容为空")
        return when {
            startsWith(bytes, ZIP_MAGIC) -> XlsxReader.read(bytes)
            startsWith(bytes, OLE2_MAGIC) -> throw ExcelFormatException(
                "这是旧版 .xls 格式，Android 端无法直接解析。\n" +
                    "请在 Excel/WPS 中「另存为」→ 选择「Excel 工作簿 (*.xlsx)」或「CSV UTF-8」后重试。"
            )
            else -> {
                val decoded = DelimitedText.decode(bytes)
                val delimiter = DelimitedText.detectDelimiter(decoded.text)
                val rows = DelimitedText.parse(decoded.text, delimiter)
                if (rows.isEmpty()) throw ExcelFormatException("文件里没有可读取的数据行")
                listOf(SheetTable(sheetNameFor(fileName), rows))
            }
        }
    }

    fun describe(bytes: ByteArray): String = when {
        startsWith(bytes, ZIP_MAGIC) -> "xlsx"
        startsWith(bytes, OLE2_MAGIC) -> "xls"
        else -> "csv"
    }

    private fun sheetNameFor(fileName: String): String =
        fileName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Sheet1" }

    private fun startsWith(bytes: ByteArray, magic: ByteArray): Boolean {
        if (bytes.size < magic.size) return false
        return magic.indices.all { bytes[it] == magic[it] }
    }
}
