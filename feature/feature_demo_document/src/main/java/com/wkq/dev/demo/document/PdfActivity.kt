package com.wkq.dev.demo.document
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.document.databinding.DemoPdfBinding
import com.wkq.router.annotation.Route
import com.wkq.photo.pdf.*
import com.wkq.photo.imagerender.ImageSource
import com.wkq.photo.core.operation.*
import kotlinx.coroutines.*
@Route(path = "/document/core_pdf")
class PdfActivity : DemoActivity<DemoPdfBinding, PdfViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_pdf_title), contentBinding.choose, contentBinding.sample, contentBinding.reverse, contentBinding.export)
        contentBinding.choose.setOnClickListener { pickImages() }
contentBinding.sample.setOnClickListener { viewModel.sample(applicationContext) }
contentBinding.reverse.setOnClickListener { viewModel.reverseInputs() }
contentBinding.export.setOnClickListener { viewModel.export(applicationContext, contentBinding.paper.text.toString(), contentBinding.margin.text.toString(), contentBinding.columns.text.toString(), contentBinding.landscape.isChecked) }

    }

}
class PdfViewModel : DemoViewModel() {
    fun export(context: Context, paper: String, margin: String, columns: String, landscape: Boolean) = execute(context) {
val files = selected(); val count = columns.toInt(); require(count in 1..4)
val output = DemoFiles.output(context, "pdf"); val job = currentCoroutineContext()
val request = PdfDocumentRequest(files.map { ImageSource.FileSource(it) }, PdfOutputTarget.FileTarget(output),
layout = if (count == 1) PdfDocumentLayout.SinglePerPage else PdfDocumentLayout.Grid(count, 2),
page = PdfPageConfig(size = PdfPageSize.valueOf(paper.trim().uppercase()), orientation = if (landscape) PdfPageOrientation.LANDSCAPE else PdfPageOrientation.PORTRAIT, marginPoints = margin.toInt()))
PdfImageExporter.executeDocument(request, cancellation = OperationCancellation { !job.isActive }, onProgress = { p -> progress((p.completedUnits * 100L / p.totalUnits.coerceAtLeast(1)).toInt()) }).getOrThrow()
DemoResult(context.getString(R.string.demo_pdf_success), output, DemoFiles.pdfPreview(context, output))
}
}
