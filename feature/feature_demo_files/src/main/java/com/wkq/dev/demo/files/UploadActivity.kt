package com.wkq.dev.demo.files
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.files.databinding.DemoUploadBinding
import com.wkq.router.annotation.Route
import com.wkq.iptc.upload.*
import com.wkq.iptc.upload.http.HttpUploader
import androidx.core.content.FileProvider
@Route(path = "/files/lib_upload")
class UploadActivity : DemoActivity<DemoUploadBinding, UploadViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_upload_title), contentBinding.choose, contentBinding.test, contentBinding.upload)
        contentBinding.choose.setOnClickListener { pickFiles("*/*") }
contentBinding.test.setOnClickListener { submit(false) }
contentBinding.upload.setOnClickListener { confirm(getString(R.string.demo_upload_intro)) { submit(true) } }

    }
    private fun submit(upload:Boolean){viewModel.submit(applicationContext,contentBinding.protocol.text.toString(),contentBinding.host.text.toString(),contentBinding.port.text.toString(),contentBinding.user.text.toString(),contentBinding.password.text.toString(),contentBinding.directory.text.toString(),upload)}
}
class UploadViewModel : DemoViewModel() {
    fun submit(context:Context,protocol:String,host:String,port:String,user:String,password:String,dir:String,upload:Boolean)=execute(context){
val type=UploadProtocolType.valueOf(protocol.trim().uppercase());val p=port.toInt();require(host.isNotBlank() && p in 1..65535)
val config:UploadServerConfig=when(type){
UploadProtocolType.FTP->FtpConfig(host,p,user,password,dir)
UploadProtocolType.FTPS->FtpsConfig(host,p,user,password,dir)
UploadProtocolType.HTTP->HttpConfig(host,p,user,password,dir)
}
val profile=UploadServerProfile(protocol=type,config=config);require(profile.isReady())
val dispatcher=UploadDispatcher(listOf(FtpUploader(context),FtpsUploader(context),HttpUploader(context)))
val result=if(upload){val file=input();val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file);dispatcher.upload(UploadTask(0,uri.toString(),file.name),profile){done,total->progress((done*100/total.coerceAtLeast(1)).toInt())}}else dispatcher.testConnection(profile)
when(result){ is UploadResult.Success->DemoResult(context.getString(R.string.demo_upload_success));is UploadResult.Failure->DemoResult(result.error.code.name) }
}
}
