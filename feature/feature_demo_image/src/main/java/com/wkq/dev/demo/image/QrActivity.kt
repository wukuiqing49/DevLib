package com.wkq.dev.demo.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.image.databinding.DemoQrBinding
import com.wkq.photo.qrcode.QrCodeKit
import com.wkq.router.annotation.Route

/** 二维码宿主页面，提供内容、尺寸和颜色配置以及图片识别入口。 */
@Route(path = "/image/core_qrcode")
class QrActivity : DemoActivity<DemoQrBinding, QrViewModel>() {
    /** 绑定素材选择、二维码生成和识别按钮，复用统一产物与预览展示。 */
    override fun initView() {
        bindDemo(
            contentBinding.result,
            getString(R.string.demo_qr_title),
            contentBinding.choose,
            contentBinding.generate,
            contentBinding.recognize,
        )
        contentBinding.choose.setOnClickListener { pickImages() }
        contentBinding.generate.setOnClickListener {
            viewModel.generate(
                applicationContext,
                contentBinding.content.text.toString(),
                contentBinding.size.text.toString(),
                contentBinding.color.text.toString(),
            )
        }
        contentBinding.recognize.setOnClickListener { viewModel.recognize(applicationContext) }
    }
}

/** 调用二维码库生成或识别内容，负责生成结果的回读校验与位图释放。 */
class QrViewModel : DemoViewModel() {
    /** 校验非空且不超过 500 字符的内容，在 IO 生成二维码并回读验证，写出 PNG 后释放位图。 */
    fun generate(context: Context, text: String, size: String, color: String) =
        execute(context) {
            require(text.isNotBlank() && text.length <= 500)
            val bitmap =
                QrCodeKit.create(
                    QrCodeKit.QrCodeRequest(text, size.toInt(), Color.parseColor(color))
                )
            val output = DemoFiles.output(context, "png")
            try {
                check(QrCodeKit.recognize(bitmap) == text.trim())
                output.outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
            DemoResult(context.getString(R.string.demo_qr_success), output, output)
        }

    /** 在 IO 识别首个素材中的码内容，未识别到时使用资源化默认提示。 */
    fun recognize(context: Context) =
        execute(context) {
            val result = QrCodeKit.recognizeAny(input())
            DemoResult(
                result?.content ?: context.getString(com.wkq.dev.demo.common.R.string.demo_ready)
            )
        }
}
