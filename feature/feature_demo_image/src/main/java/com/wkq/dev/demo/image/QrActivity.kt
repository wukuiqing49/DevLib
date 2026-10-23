package com.wkq.dev.demo.image
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.image.databinding.DemoQrBinding
import com.wkq.router.annotation.Route
import com.wkq.photo.qrcode.QrCodeKit
import android.graphics.Bitmap
import android.graphics.Color
@Route(path = "/image/core_qrcode")
class QrActivity : DemoActivity<DemoQrBinding, QrViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_qr_title), contentBinding.choose, contentBinding.generate, contentBinding.recognize)
        contentBinding.choose.setOnClickListener { pickImages() }
contentBinding.generate.setOnClickListener { viewModel.generate(applicationContext, contentBinding.content.text.toString(),contentBinding.size.text.toString(),contentBinding.color.text.toString()) }
contentBinding.recognize.setOnClickListener { viewModel.recognize(applicationContext) }

    }

}
class QrViewModel : DemoViewModel() {
    fun generate(context:Context, text:String,size:String,color:String)=execute(context){
require(text.isNotBlank() && text.length<=500); val bitmap=QrCodeKit.create(QrCodeKit.QrCodeRequest(text,size.toInt(),Color.parseColor(color))); val output=DemoFiles.output(context,"png")
try { check(QrCodeKit.recognize(bitmap)==text.trim()); output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { bitmap.recycle() }
DemoResult(context.getString(R.string.demo_qr_success),output,output)
}
fun recognize(context:Context)=execute(context){ val result=QrCodeKit.recognizeAny(input()); DemoResult(result?.content ?: context.getString(com.wkq.dev.demo.common.R.string.demo_ready)) }
}
