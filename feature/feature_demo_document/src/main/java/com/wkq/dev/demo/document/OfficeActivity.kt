package com.wkq.dev.demo.document

import android.content.Context
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.document.databinding.DemoOfficeBinding
import com.wkq.roof.export.excel.SimpleXlsxWriter
import com.wkq.roof.export.word.SimpleDocxWriter
import com.wkq.router.annotation.Route
import java.util.zip.ZipFile

/** 文档编辑宿主页，复用 DemoActivity 展示表格输入并导出 Word 或 Excel。 */
@Route(path = "/document/lib_document")
class OfficeActivity : DemoActivity<DemoOfficeBinding, OfficeViewModel>() {
    /** 绑定单元格编辑、行列增删和导出操作，使用基础列表适配器展示当前表格。 */
    override fun initView() {
        val b = contentBinding
        bindDemo(
            b.result,
            getString(R.string.demo_office_title),
            b.word,
            b.excel,
            b.addRow,
            b.addColumn,
            b.removeColumn,
            b.table,
        )
        val adapter =
            object :
                com.wkq.base.adapter.BaseRecyclerViewAdapter<
                    com.wkq.dev.demo.document.databinding.ItemOfficeRowBinding,
                    List<String>,
                >(this, com.wkq.dev.demo.document.databinding.ItemOfficeRowBinding::inflate) {
                /** 重建当前行的可编辑单元格并绑定行删除确认，编辑值同步到 ViewModel。 */
                override fun convert(
                    binding: com.wkq.dev.demo.document.databinding.ItemOfficeRowBinding,
                    item: List<String>,
                    position: Int,
                ) {
                    binding.cells.removeAllViews()
                    item.forEachIndexed { column, value ->
                        val input =
                            com.google.android.material.textfield
                                .TextInputEditText(this@OfficeActivity)
                                .apply {
                                    layoutParams =
                                        android.widget.LinearLayout.LayoutParams(
                                            (140 * resources.displayMetrics.density).toInt(),
                                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                        )
                                    minHeight = (56 * resources.displayMetrics.density).toInt()
                                    inputType =
                                        android.text.InputType.TYPE_CLASS_TEXT or
                                            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                    hint = getString(R.string.office_cell, position + 1, column + 1)
                                    setText(value)
                                    addTextChangedListener(
                                        object : android.text.TextWatcher {
                                            /** 无需保存旧单元格文本，实际编辑更新由 onTextChanged 负责。 */
                                            override fun beforeTextChanged(
                                                s: CharSequence?,
                                                start: Int,
                                                count: Int,
                                                after: Int,
                                            ) = Unit

                                            /** 将当前行列的编辑内容同步到可恢复的表格状态。 */
                                            override fun onTextChanged(
                                                s: CharSequence?,
                                                start: Int,
                                                before: Int,
                                                count: Int,
                                            ) {
                                                viewModel.cell(position, column, s.toString())
                                            }

                                            /** 文本已在变更回调中保存，此处不再次写入以避免重复更新。 */
                                            override fun afterTextChanged(
                                                s: android.text.Editable?
                                            ) = Unit
                                        }
                                    )
                                }
                        binding.cells.addView(input)
                    }
                    binding.remove.isEnabled = viewModel.table.rows.size > 1
                    binding.remove.setOnClickListener {
                        confirm(getString(R.string.office_remove_confirm)) {
                            viewModel.removeRow(position)
                            refreshTable()
                        }
                    }
                }
            }
        b.table.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        b.table.adapter = adapter
        b.addRow.setOnClickListener {
            viewModel.addRow()
            refreshTable()
        }
        b.addColumn.setOnClickListener {
            viewModel.addColumn()
            refreshTable()
        }
        b.removeColumn.setOnClickListener {
            confirm(getString(R.string.office_column_confirm)) {
                viewModel.removeColumn()
                refreshTable()
            }
        }
        b.word.setOnClickListener {
            viewModel.export(
                applicationContext,
                b.title.text.toString(),
                b.body.text.toString(),
                false,
            )
        }
        b.excel.setOnClickListener {
            viewModel.export(
                applicationContext,
                b.title.text.toString(),
                b.body.text.toString(),
                true,
            )
        }
        refreshTable()
    }

    /** 将 ViewModel 表格重新提交给适配器，并更新行列数量提示。 */
    @Suppress("UNCHECKED_CAST")
    private fun refreshTable() {
        (contentBinding.table.adapter
                as com.wkq.base.adapter.BaseRecyclerViewAdapter<*, List<String>>)
            .setData(viewModel.table.rows)
        contentBinding.tableStatus.text =
            getString(
                R.string.office_table_status,
                viewModel.table.rows.size,
                viewModel.table.rows.first().size,
            )
    }
}

/** 保存可恢复的表格编辑状态，复用文档库在 IO 导出并检查 Office 文件结构。 */
class OfficeViewModel(private val saved: androidx.lifecycle.SavedStateHandle) : DemoViewModel() {
    var table =
        DocumentTable(
            saved.get<ArrayList<ArrayList<String>>>("table")
                ?: listOf(listOf("", ""), listOf("", ""))
        )
        private set

    /** 替换表格模型并将各行写入 SavedStateHandle，支持页面重建恢复。 */
    private fun update(value: DocumentTable) {
        table = value
        saved["table"] = ArrayList(value.rows.map { ArrayList(it) })
    }

    /** 更新指定单元格并保存最新表格快照。 */
    fun cell(row: Int, column: Int, text: String) {
        update(table.cell(row, column, text))
    }

    /** 按表格行数上限添加空行，并保存可恢复状态。 */
    fun addRow() {
        update(table.addRow())
    }

    /** 按表格列数上限添加空列，并保存可恢复状态。 */
    fun addColumn() {
        update(table.addColumn())
    }

    /** 按模型约束删除指定行，至少保留一行并保存状态。 */
    fun removeRow(index: Int) {
        update(table.removeRow(index))
    }

    /** 移除末列但保留最小列数，将变化保存到恢复状态。 */
    fun removeColumn() {
        update(table.removeColumn())
    }

    /** 校验标题并在 IO 导出表格为 XLSX 或 DOCX，检查 ZIP 内关键文档条目后发布产物。 */
    fun export(context: Context, title: String, body: String, excel: Boolean) =
        execute(context) {
            require(title.isNotBlank())
            val table = table.rows.map { it.toList() }
            require(table.isNotEmpty())
            val output = DemoFiles.output(context, if (excel) "xlsx" else "docx")
            if (excel)
                SimpleXlsxWriter.write(
                    output,
                    listOf(
                        SimpleXlsxWriter.Sheet(
                            title.take(31),
                            table.map { row -> row.map { SimpleXlsxWriter.Cell(it) } },
                        )
                    ),
                )
            else
                SimpleDocxWriter.write(
                    output,
                    SimpleDocxWriter.Document(
                        listOf(
                            SimpleDocxWriter.Paragraph(title),
                            SimpleDocxWriter.Paragraph(body),
                            SimpleDocxWriter.Table(table),
                        )
                    ),
                )
            ZipFile(output).use {
                check(
                    it.getEntry(if (excel) "xl/worksheets/sheet1.xml" else "word/document.xml") !=
                        null
                )
            }
            DemoResult(context.getString(R.string.demo_office_success), output)
        }
}
