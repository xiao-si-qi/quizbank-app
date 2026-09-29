package com.xiaosiqi.quizbank.excel

/**
 * 一张表格的纯字符串视图。所有解析器都只依赖这个模型，
 * 因此 XLSX / CSV / TSV 可以走完全相同的导入流程。
 */
class SheetTable(
    val name: String,
    val rows: List<List<String>>,
) {
    val rowCount: Int get() = rows.size

    fun row(i: Int): List<String> = rows.getOrNull(i) ?: emptyList()

    fun cell(r: Int, c: Int): String = rows.getOrNull(r)?.getOrNull(c)?.trim().orEmpty()

    fun columnCount(): Int = rows.maxOfOrNull { it.size } ?: 0

    /** 只保留至少有一个非空单元格的行，同时保留原始行号（0 基）。 */
    fun nonEmptyRows(): List<Pair<Int, List<String>>> =
        rows.withIndex().filter { (_, r) -> r.any { it.isNotBlank() } }.map { it.index to it.value }

    override fun toString(): String = "SheetTable($name, ${rowCount}x${columnCount()})"
}
