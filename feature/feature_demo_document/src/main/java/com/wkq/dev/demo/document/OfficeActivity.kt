package com.wkq.dev.demo.document
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.document.databinding.DemoOfficeBinding
import com.wkq.router.annotation.Route
import com.wkq.roof.export.word.SimpleDocxWriter
import com.wkq.roof.export.excel.SimpleXlsxWriter
import java.util.zip.ZipFile
@Route(path = "/document/lib_document")
class OfficeActivity : DemoActivity<DemoOfficeBinding, OfficeViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_office_title), contentBinding.word, contentBinding.excel)
        contentBinding.word.setOnClickListener { viewModel.export(applicationContext, contentBinding.title.text.toString(), contentBinding.body.text.toString(), contentBinding.rows.text.toString(), false) }
contentBinding.excel.setOnClickListener { viewModel.export(applicationContext, contentBinding.title.text.toString(), contentBinding.body.text.toString(), contentBinding.rows.text.toString(), true) }

    }

}
class OfficeViewModel : DemoViewModel() {
    fun export(context: Context, title: String, body: String, rows: String, excel: Boolean) = execute(context) {
require(title.isNotBlank()); val table = rows.lines().filter { it.isNotBlank() }.map { it.split(',') }; require(table.isNotEmpty())
val output = DemoFiles.output(context, if(excel) "xlsx" else "docx")
if(excel) SimpleXlsxWriter.write(output, listOf(SimpleXlsxWriter.Sheet(title.take(31), table.map { row -> row.map { SimpleXlsxWriter.Cell(it) } })))
else SimpleDocxWriter.write(output, SimpleDocxWriter.Document(listOf(SimpleDocxWriter.Paragraph(title), SimpleDocxWriter.Paragraph(body), SimpleDocxWriter.Table(table))))
ZipFile(output).use { check(it.getEntry(if(excel) "xl/worksheets/sheet1.xml" else "word/document.xml") != null) }
DemoResult(context.getString(R.string.demo_office_success), output)
}
}
