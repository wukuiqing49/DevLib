package com.wkq.dev.demo.document

/** 文档宿主编辑模型，单元格不再以逗号分割。 */
data class DocumentTable(val rows: List<List<String>>) {
    init {
        require(
            rows.isNotEmpty() &&
                rows.size <= 30 &&
                rows.first().size in 1..8 &&
                rows.all { it.size == rows.first().size }
        )
    }

    /** 返回指定单元格更新后的不可变表格副本，其他行列保持原值。 */
    fun cell(row: Int, column: Int, text: String) =
        copy(
            rows =
                rows.mapIndexed { r, cells ->
                    if (r == row) cells.mapIndexed { c, value -> if (c == column) text else value }
                    else cells
                }
        )

    /** 在末尾添加与当前列数一致的空行，达到 30 行时保留原表。 */
    fun addRow() =
        if (rows.size < 30) copy(rows = rows + listOf(List(rows.first().size) { "" })) else this

    /** 为每行追加一个空单元格，达到 8 列时保留原表。 */
    fun addColumn() = if (rows.first().size < 8) copy(rows = rows.map { it + "" }) else this

    /** 删除指定行但至少保留一行，无效索引不改变内容。 */
    fun removeRow(index: Int) =
        if (rows.size > 1) copy(rows = rows.filterIndexed { i, _ -> i != index }) else this

    /** 删除末列但至少保留一列，维持矩形表格结构。 */
    fun removeColumn() =
        if (rows.first().size > 1) copy(rows = rows.map { it.dropLast(1) }) else this
}
