package com.wkq.dev.demo.image

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.image.databinding.DemoWatermarkBinding
import com.wkq.photo.core.operation.*
import com.wkq.photo.imagerender.*
import com.wkq.router.annotation.Route
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 轻量图片处理宿主页，配置水印、相框、背景、签名提取与文字标注参数。 */
@Route(path = "/image/core_image_render")
class WatermarkActivity : DemoActivity<DemoWatermarkBinding, WatermarkViewModel>() {
    /** 绑定模式、样式和图层历史操作，将当前参数提交到 ViewModel 并观察图层数量。 */
    override fun initView() {
        val b = contentBinding
        bindDemo(
            b.result,
            getString(R.string.demo_watermark_title),
            b.choose,
            b.sample,
            b.render,
            b.addLabel,
            b.undo,
            b.redo,
            b.clear,
        )
        b.choose.setOnClickListener { pickImages() }
        b.sample.setOnClickListener { viewModel.sample(applicationContext) }
        b.mode.adapter =
            ArrayAdapter.createFromResource(
                this,
                R.array.demo_image_modes,
                android.R.layout.simple_spinner_dropdown_item,
            )
        b.mode.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                /** 未选择模式时保留当前表单，等待有效模式回调。 */
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit

                /** 按处理模式设置样式列表，并显示适用的文字、透明度、阈值或标注控件。 */
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val styles =
                        when (position) {
                            1 -> R.array.demo_image_frames
                            2 -> R.array.demo_image_backgrounds
                            3 -> R.array.demo_image_inks
                            else -> R.array.demo_image_default
                        }
                    b.styleChoice.adapter =
                        ArrayAdapter.createFromResource(
                            this@WatermarkActivity,
                            styles,
                            android.R.layout.simple_spinner_dropdown_item,
                        )
                    b.styleChoice.visibility = if (position in 1..3) View.VISIBLE else View.GONE
                    b.textInput.visibility =
                        if (position == 0 || position == 1 || position == 4) View.VISIBLE
                        else View.GONE
                    b.sizeInput.visibility = if (position == 0) View.VISIBLE else View.GONE
                    b.alphaInput.visibility = if (position == 0) View.VISIBLE else View.GONE
                    b.thresholdInput.visibility = if (position == 3) View.VISIBLE else View.GONE
                    b.annotations.visibility = if (position == 4) View.VISIBLE else View.GONE
                }
            }
        b.addLabel.setOnClickListener {
            viewModel.addLabel(applicationContext, b.text.text.toString())
        }
        b.undo.setOnClickListener { viewModel.undo() }
        b.redo.setOnClickListener { viewModel.redo() }
        b.clear.setOnClickListener { viewModel.clear() }
        b.render.setOnClickListener {
            viewModel.render(
                applicationContext,
                b.mode.selectedItemPosition,
                b.styleChoice.selectedItemPosition,
                b.text.text.toString(),
                b.size.text.toString(),
                b.alpha.text.toString(),
                b.threshold.text.toString(),
            )
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.layerCount.collect {
                    b.annotationStatus.text = getString(R.string.demo_image_layers, it)
                }
            }
        }
    }
}

/** 管理最多 30 个标注历史状态，并将各模式参数转换为图片库请求。 */
class WatermarkViewModel : DemoViewModel() {
    private val history = AnnotationHistory(maxStates = 30)
    private val mutableLayerCount = MutableStateFlow(0)
    val layerCount = mutableLayerCount.asStateFlow()

    /** 非忙碌时追加归一化位置的文字图层，拒绝空文本或超过 20 层，并更新历史和层数。 */
    fun addLabel(context: Context, text: String) {
        if (state.value.busy) return
        if (text.isBlank() || history.current.layers.size >= 20) {
            report(context.getString(R.string.demo_image_label_required))
            return
        }
        val y = 0.08f + (history.current.layers.size % 5) * 0.15f
        val label =
            AnnotationLayer.Text(
                NormalizedRect(0.06f, y, 0.94f, y + 0.12f),
                text.trim(),
                Color.RED,
                0.06f,
            )
        history.update(AnnotationDocument(history.current.layers + label))
        mutableLayerCount.value = history.current.layers.size
    }

    /** 非忙碌时撤销一次标注编辑，并同步图层数量。 */
    fun undo() {
        if (!state.value.busy) {
            history.undo()
            mutableLayerCount.value = history.current.layers.size
        }
    }

    /** 非忙碌时重做一次标注编辑，并同步图层数量。 */
    fun redo() {
        if (!state.value.busy) {
            history.redo()
            mutableLayerCount.value = history.current.layers.size
        }
    }

    /** 非忙碌时把空标注文档写入历史，清零显示层数并保留撤销能力。 */
    fun clear() {
        if (!state.value.busy) {
            history.update(AnnotationDocument())
            mutableLayerCount.value = 0
        }
    }

    /** 捕获当前标注文档，在 IO 按模式校验参数并输出 PNG；统一向引擎传递取消与进度回调。 */
    fun render(
        context: Context,
        mode: Int,
        style: Int,
        text: String,
        size: String,
        alpha: String,
        threshold: String,
    ) {
        val document = history.current
        execute(context) {
            val source = ImageSource.FileSource(input())
            val output = DemoFiles.output(context, "png")
            val job = currentCoroutineContext()
            val cancellation = OperationCancellation { !job.isActive }
            val onProgress: (OperationProgress) -> Unit = { p ->
                progress((p.completedUnits * 100L / p.totalUnits.coerceAtLeast(1)).toInt())
            }
            when (mode) {
                0 -> {
                    val ratio = size.toFloatOrNull()
                    val opacity = alpha.toIntOrNull()
                    if (
                        text.isBlank() ||
                            ratio == null ||
                            ratio !in 0.01f..0.5f ||
                            opacity == null ||
                            opacity !in 0..255
                    ) {
                        throw DemoInputException(
                            context.getString(R.string.demo_image_watermark_invalid)
                        )
                    }
                    WatermarkRenderer.renderResult(
                            source,
                            output,
                            WatermarkContent.Text(text),
                            WatermarkSpec(WatermarkPosition.BOTTOM_END, ratio, opacity),
                            encodeOptions = ImageEncodeOptions(format = ImageOutputFormat.PNG),
                            cancellation = cancellation,
                            onProgress = onProgress,
                        )
                        .getOrThrow()
                }
                1 ->
                    PolaroidFrameRenderer.render(
                            source,
                            PolaroidStyle.entries[style.coerceIn(0, 3)],
                            text.ifBlank { context.getString(R.string.demo_watermark_text_value) },
                            output,
                            ImageOutputFormat.PNG,
                            cancellation = cancellation,
                            onProgress = onProgress,
                        )
                        .getOrThrow()
                2 ->
                    DeviceMockupRenderer.render(
                            source,
                            MockupBackground.entries[style.coerceIn(0, 3)],
                            output,
                            cancellation = cancellation,
                            onProgress = onProgress,
                        )
                        .getOrThrow()
                3 -> {
                    val value = threshold.toFloatOrNull()
                    if (value == null || value !in 0.1f..1f)
                        throw DemoInputException(
                            context.getString(R.string.demo_image_threshold_invalid)
                        )
                    SignatureExtractorRenderer.extract(
                            source,
                            SignatureInkColor.entries[style.coerceIn(0, 2)],
                            output,
                            thresholdFactor = value,
                            cancellation = cancellation,
                            onProgress = onProgress,
                        )
                        .getOrThrow()
                }
                4 -> {
                    if (document.layers.isEmpty())
                        throw DemoInputException(context.getString(R.string.demo_image_add_first))
                    AnnotationRenderer.render(
                            source,
                            output,
                            document,
                            ImageEncodeOptions(format = ImageOutputFormat.PNG),
                            cancellation = cancellation,
                            onProgress = onProgress,
                        )
                        .getOrThrow()
                }
                else ->
                    throw DemoInputException(context.getString(R.string.demo_image_mode_required))
            }
            DemoResult(context.getString(R.string.demo_watermark_success), output, output)
        }
    }
}
