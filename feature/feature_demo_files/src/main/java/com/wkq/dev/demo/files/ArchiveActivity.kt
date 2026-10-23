package com.wkq.dev.demo.files
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.files.databinding.DemoArchiveBinding
import com.wkq.router.annotation.Route
import com.wkq.photo.archive.*
import com.wkq.photo.core.operation.*
import kotlinx.coroutines.*
@Route(path = "/files/core_archive")
class ArchiveActivity : DemoActivity<DemoArchiveBinding, ArchiveViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_archive_title), contentBinding.choose, contentBinding.sample, contentBinding.compress, contentBinding.extract)
        contentBinding.choose.setOnClickListener { pickFiles("*/*") }
contentBinding.sample.setOnClickListener { viewModel.sample(applicationContext) }
contentBinding.compress.setOnClickListener { viewModel.compress(applicationContext,contentBinding.password.text.toString()) }
contentBinding.extract.setOnClickListener { viewModel.extract(applicationContext,contentBinding.password.text.toString()) }

    }

}
class ArchiveViewModel : DemoViewModel() {
    fun compress(context:Context,password:String)=execute(context){
val out=DemoFiles.output(context,"zip");val request=ZipArchiveRequest(selected(),out);val job=currentCoroutineContext()
if(password.isEmpty()) ZipArchiveCreator.create(request,isCancelled={!job.isActive}) else {val chars=password.toCharArray();try{ZipArchiveCreator.createEncrypted(request,chars,isCancelled={!job.isActive})}finally{chars.fill('\u0000')}}
DemoResult(context.getString(R.string.demo_archive_success),out)
}
fun extract(context:Context,password:String)=execute(context){val folder=DemoFiles.output(context,"dir");val chars=password.toCharArray();val job=currentCoroutineContext();try{val result=EncryptedArchiveExtractor.extract(EncryptedArchiveExtractRequest(input(),folder,chars),cancellation=OperationCancellation{!job.isActive}).getOrThrow();DemoResult(result.files.joinToString("\n"){it.name+" (${it.length()} B)"})}finally{chars.fill('\u0000')}}
}
