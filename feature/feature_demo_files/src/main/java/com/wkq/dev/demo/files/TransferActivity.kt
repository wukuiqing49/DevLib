package com.wkq.dev.demo.files
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.files.databinding.DemoTransferBinding
import com.wkq.router.annotation.Route
import com.wkq.nas.transfer.*
import java.io.File
@Route(path = "/files/lib_transfer")
class TransferActivity : DemoActivity<DemoTransferBinding, TransferViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_transfer_title), contentBinding.choose, contentBinding.sample, contentBinding.enqueue, contentBinding.refresh, contentBinding.pause, contentBinding.resume, contentBinding.canceljob)
        contentBinding.choose.setOnClickListener { pickFiles("*/*") }
contentBinding.sample.setOnClickListener { viewModel.sample(applicationContext) }
contentBinding.enqueue.setOnClickListener { viewModel.enqueue(applicationContext,contentBinding.name.text.toString()) }
contentBinding.refresh.setOnClickListener { viewModel.refresh(applicationContext) }
contentBinding.pause.setOnClickListener { viewModel.control(applicationContext,0) }
contentBinding.resume.setOnClickListener { viewModel.control(applicationContext,1) }
contentBinding.canceljob.setOnClickListener { viewModel.control(applicationContext,2) }

    }

}
class TransferViewModel : DemoViewModel() {
    private var taskId:String?=null
fun enqueue(context:Context,name:String)=execute(context){require(name.isNotBlank() && !name.contains('/') && !name.contains('\\'));val source=input();val target=File(context.filesDir,"shared/transfer-demo").apply{mkdirs()};val client=TransferClient.get(context);val from="demo-source-${source.parentFile!!.name}";val to="demo-target";client.saveConnection(from,ConnectionConfig(StorageProtocol.LOCAL,source.parent!!));client.saveConnection(to,ConnectionConfig(StorageProtocol.LOCAL,target.path));taskId=client.enqueue(TransferRequest(from,source.name,to,name,ConflictPolicy.KEEP_BOTH,verifyContent=true));DemoResult("Task: $taskId")}
fun refresh(context:Context)=execute(context){DemoResult(TransferClient.get(context).getTask(requireNotNull(taskId)).toString())}
fun control(context:Context,action:Int)=execute(context){val client=TransferClient.get(context);val id=requireNotNull(taskId);val result=when(action){0->client.pause(id);1->client.resume(id);else->client.cancel(id)};DemoResult("Task: $id · $result")}
}
