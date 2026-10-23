package com.wkq.dev.demo.image
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.image.databinding.DemoWatermarkBinding
import com.wkq.router.annotation.Route
import com.wkq.photo.imagerender.*
@Route(path = "/image/core_image_render")
class WatermarkActivity : DemoActivity<DemoWatermarkBinding, WatermarkViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_watermark_title), contentBinding.choose, contentBinding.sample, contentBinding.render)
        contentBinding.choose.setOnClickListener { pickImages() }
contentBinding.sample.setOnClickListener { viewModel.sample(applicationContext) }
contentBinding.render.setOnClickListener { viewModel.render(applicationContext, contentBinding.text.text.toString(), contentBinding.size.text.toString(), contentBinding.alpha.text.toString()) }

    }

}
class WatermarkViewModel : DemoViewModel() {
    fun render(context: Context, text: String, size: String, alpha: String) = execute(context) {
val output=DemoFiles.output(context,"jpg"); WatermarkRenderer.render(input(), output, WatermarkContent.Text(text), WatermarkSpec(WatermarkPosition.BOTTOM_END,size.toFloat(),alpha.toInt()))
DemoResult(context.getString(R.string.demo_watermark_success),output,output)
}
}
