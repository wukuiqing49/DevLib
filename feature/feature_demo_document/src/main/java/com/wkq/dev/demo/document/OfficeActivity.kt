package com.wkq.dev.demo.document

import android.content.Context
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.document.databinding.DemoOfficeBinding
import com.wkq.dev.document.*
import kotlinx.coroutines.runInterruptible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.io.File
import com.wkq.router.annotation.Route

/** 记录导出宿主页，复用 Base、统一输入样式与工作区，生成同一记录的三种文件。 */
@Route(path = "/document/lib_document")
class OfficeActivity : DemoActivity<DemoOfficeBinding, OfficeViewModel>() {
    private val settingsLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data?.getBooleanExtra("preview", false) == true) export(setOf(RecordFormat.PDF), true)
    }
    /** 绑定单元格编辑、行列增删和导出操作，使用基础列表适配器展示当前表格。 */
    override fun initView() {
        val b = contentBinding
        bindDemo(
            b.result,
            getString(R.string.demo_office_title),
            b.recordSettings,
            b.previewRecord,
            b.pdf,
            b.allFormats,
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
                        val cellBinding = com.wkq.dev.demo.document.databinding.ItemOfficeCellBinding.inflate(layoutInflater, binding.cells, false)
                        cellBinding.cell.apply {
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
                        binding.cells.addView(cellBinding.root)
                    }
                    binding.remove.isEnabled = viewModel.table.rows.size > 1 && !viewModel.state.value.busy
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
            export(setOf(RecordFormat.WORD))
        }
        b.excel.setOnClickListener {
            export(setOf(RecordFormat.EXCEL))
        }
        b.recordSettings.setOnClickListener { settingsLauncher.launch(android.content.Intent(this, RecordSettingsActivity::class.java)) }
        b.previewRecord.setOnClickListener { export(setOf(RecordFormat.PDF), true) }
        b.pdf.setOnClickListener { export(setOf(RecordFormat.PDF)) }
        b.allFormats.setOnClickListener { export(RecordFormat.entries.toSet()) }
        b.imagePdf.setOnClickListener { startActivity(android.content.Intent(this, PdfActivity::class.java)) }
        b.addImages.setOnClickListener { pickImages() }
        b.clearImages.setOnClickListener { viewModel.state.value.inputs.indices.reversed().forEach { viewModel.removeInput(it) } }
        b.recordedAt.setText(viewModel.recordedAt)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    setEditorEnabled(b.table, !state.busy)
                    b.title.isEnabled = !state.busy
                    b.body.isEnabled = !state.busy
                    b.recordedAt.isEnabled = !state.busy
                    b.numeric.isEnabled = !state.busy
                    b.addImages.isEnabled = !state.busy
                    b.clearImages.isEnabled = !state.busy
                    b.attachments.text = getString(R.string.record_attachments, state.inputs.size)
                }
            }
        }
        refreshTable()
    }

    /** 捕获当前表单作为记录快照，显式数字开关只作用于表头后的单元格。 */
    private fun export(formats: Set<RecordFormat>, preview: Boolean = false) {
        val b = contentBinding
        viewModel.export(applicationContext, b.title.text.toString(), b.body.text.toString(),
            b.recordedAt.text.toString(), b.numeric.isChecked, formats, preview)
    }

    /** 递归控制编辑区，避免只禁用 RecyclerView 而遗漏子输入框和删除按钮。 */
    private fun setEditorEnabled(view: android.view.View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) setEditorEnabled(view.getChildAt(index), enabled)
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

/** 保存可恢复的记录编辑状态，在 IO 线程调用统一导出库。 */
class OfficeViewModel(private val saved: androidx.lifecycle.SavedStateHandle) : DemoViewModel() {
    val recordedAt: String = saved.get<String>("recordedAt") ?: java.text.DateFormat.getDateTimeInstance().format(java.util.Date()).also { saved["recordedAt"] = it }
    var table =
        DocumentTable(
            saved.get<ArrayList<ArrayList<String>>>("table")
                ?: listOf(listOf("", ""), listOf("", ""))
        )
        private set

    /** 替换表格模型并将各行写入 SavedStateHandle，支持页面重建恢复。 */
    private fun update(value: DocumentTable) {
        if (state.value.busy) return
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

    /** 同一份记录导出选定格式；可中断阻塞写入，整批失败不发布半成品。 */
    fun export(context: Context, title: String, body: String, time: String, numeric: Boolean, formats: Set<RecordFormat>, preview: Boolean = false) {
        val record = ExportRecord(title, time, body,
            rows = table.rows.mapIndexed { index, row -> row.map { value ->
                RecordCell(value, numeric && index > 0 && value.isNotBlank() && value.toBigDecimalOrNull() != null)
            } }, images = state.value.inputs.map { RecordImage(it, it.name) })
        saved["recordedAt"] = time
        execute(context) {
            val configured = RecordSettingsStore(context).load()
            val snapshot = record.copy(company = configured.company, person = configured.person,
                signatures = configured.signatures.toList(), template = configured.selected)
            val files = runInterruptible {
                RecordExporter.export(snapshot, File(context.filesDir, "shared/records"), formats) { done, total -> progress(done * 100 / total) }
            }
            val image = if (preview) DemoFiles.pdfPreview(context, files.single()) else null
            DemoResult(context.resources.getQuantityString(R.plurals.record_success, files.size, files.size), files = files, preview = image)
        }
    }
}
