package com.wkq.dev.demo.document

import android.content.Context
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.document.databinding.DemoPdfBinding
import com.wkq.photo.core.operation.*
import com.wkq.photo.imagerender.ImageSource
import com.wkq.photo.pdf.*
import com.wkq.router.annotation.Route
import kotlinx.coroutines.*

/** 图片转 PDF 的宿主页面，提供素材页序调整、纸张布局配置和导出预览。 */
@Route(path = "/document/core_pdf")
class PdfActivity : DemoActivity<DemoPdfBinding, PdfViewModel>() {
    /** 绑定分页素材列表与选择、排序、导出按钮，在 STARTED 期间同步输入状态。 */
    override fun initView() {
        val pageAdapter =
            object :
                com.wkq.base.adapter.BaseRecyclerViewAdapter<
                    com.wkq.dev.demo.document.databinding.ItemPdfPageBinding,
                    java.io.File,
                >(this, com.wkq.dev.demo.document.databinding.ItemPdfPageBinding::inflate) {
                /** 展示当前页素材及预览，按位置和忙碌状态启用上移、下移与移除按钮。 */
                override fun convert(
                    binding: com.wkq.dev.demo.document.databinding.ItemPdfPageBinding,
                    item: java.io.File,
                    position: Int,
                ) {
                    binding.title.text =
                        getString(
                            R.string.pdf_page_item,
                            position + 1,
                            viewModel.state.value.names[item.absolutePath] ?: item.name,
                        )
                    com.wkq.util.coil.ImageLoaderUtil.load(binding.preview, item)
                    binding.up.isEnabled = position > 0 && !viewModel.state.value.busy
                    binding.down.isEnabled =
                        position < viewModel.state.value.inputs.lastIndex &&
                            !viewModel.state.value.busy
                    binding.remove.isEnabled = !viewModel.state.value.busy
                    binding.up.setOnClickListener { viewModel.moveInput(position, -1) }
                    binding.down.setOnClickListener { viewModel.moveInput(position, 1) }
                    binding.remove.setOnClickListener { viewModel.removeInput(position) }
                }
            }
        contentBinding.pages.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        contentBinding.pages.adapter = pageAdapter
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.state.collect { pageAdapter.setData(it.inputs) }
            }
        }
        bindDemo(
            contentBinding.result,
            getString(R.string.demo_pdf_title),
            contentBinding.choose,
            contentBinding.sample,
            contentBinding.reverse,
            contentBinding.export,
        )
        contentBinding.choose.setOnClickListener { pickImages() }
        contentBinding.sample.setOnClickListener { viewModel.sample(applicationContext) }
        contentBinding.reverse.setOnClickListener { viewModel.reverseInputs() }
        contentBinding.export.setOnClickListener {
            viewModel.export(
                applicationContext,
                contentBinding.paper.text.toString(),
                contentBinding.margin.text.toString(),
                contentBinding.columns.text.toString(),
                contentBinding.landscape.isChecked,
            )
        }
    }
}

/** 将宿主输入和页面参数转换为 PDF 库请求，管理导出进度、取消和首页预览。 */
class PdfViewModel : DemoViewModel() {
    /** 在 IO 校验列数并按所选页序导出 PDF，将协程取消传给引擎；成功后生成首页预览。 */
    fun export(
        context: Context,
        paper: String,
        margin: String,
        columns: String,
        landscape: Boolean,
    ) =
        execute(context) {
            val files = selected()
            val count = columns.toInt()
            require(count in 1..4)
            val output = DemoFiles.output(context, "pdf")
            val job = currentCoroutineContext()
            val request =
                PdfDocumentRequest(
                    files.map { ImageSource.FileSource(it) },
                    PdfOutputTarget.FileTarget(output),
                    layout =
                        if (count == 1) PdfDocumentLayout.SinglePerPage
                        else PdfDocumentLayout.Grid(count, 2),
                    page =
                        PdfPageConfig(
                            size = PdfPageSize.valueOf(paper.trim().uppercase()),
                            orientation =
                                if (landscape) PdfPageOrientation.LANDSCAPE
                                else PdfPageOrientation.PORTRAIT,
                            marginPoints = margin.toInt(),
                        ),
                )
            PdfImageExporter.executeDocument(
                    request,
                    cancellation = OperationCancellation { !job.isActive },
                    onProgress = { p ->
                        progress((p.completedUnits * 100L / p.totalUnits.coerceAtLeast(1)).toInt())
                    },
                )
                .getOrThrow()
            DemoResult(
                context.getString(R.string.demo_pdf_success),
                output,
                DemoFiles.pdfPreview(context, output),
            )
        }
}
