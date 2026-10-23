package com.wkq.dev.demo.files
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.files.databinding.DemoDropBinding
import com.wkq.router.annotation.Route
import com.wkq.droplan.drop.*
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
@Route(path = "/files/lib_drop")
class DropActivity : DemoActivity<DemoDropBinding, DropViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_drop_title), contentBinding.choose, contentBinding.sample, contentBinding.start, contentBinding.approve, contentBinding.stop)
        contentBinding.choose.setOnClickListener { pickFiles("*/*") }
contentBinding.sample.setOnClickListener { viewModel.sample(applicationContext) }
contentBinding.start.setOnClickListener { startSharing() }
contentBinding.approve.setOnClickListener { viewModel.approve() }
contentBinding.stop.setOnClickListener { viewModel.stop() }

    }
    private fun startSharing(){lifecycleScope.launch{val addresses=withContext(Dispatchers.IO){LocalAddressDiscovery.candidates(applicationContext).filter{it.recognizedLan}.map{it.address}};if(addresses.isEmpty()){message(getString(R.string.demo_drop_intro));return@launch};com.lxj.xpopup.XPopup.Builder(this@DropActivity).asBottomList(getString(R.string.demo_drop_start),addresses.toTypedArray()){index,_->viewModel.start(applicationContext,addresses[index])}.show()}}
override fun onStop(){viewModel.stop();super.onStop()}
}
class DropViewModel : DemoViewModel() {
    private var session:Job?=null
@Volatile private var engine:ShareEngine?=null
fun start(context:Context,address:String){if(session?.isCompleted==false)return;val files=state.value.inputs;if(files.isEmpty())return;session=viewModelScope.launch{withContext(Dispatchers.IO){var server:DropServer?=null;var sharing:ShareEngine?=null;try{sharing=ShareEngine(files.map{UriShareSource.create(context.contentResolver,FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",it))});engine=sharing;server=DropServer(address,0,sharing,DemoReceiverAssets.provider(context));server.start();while(isActive){val text=server.entryUrl+"\nPending: ${sharing.pendingPairings().size} · Receivers: ${sharing.receivers().size}\n"+sharing.senderTasks().joinToString("\n"){it.name+" "+it.download.toString()};withContext(Dispatchers.Main){report(text)};delay(1000)}}catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){withContext(Dispatchers.Main){report(context.getString(com.wkq.dev.demo.common.R.string.demo_failed,error.javaClass.simpleName))}}finally{server?.close();sharing?.close();engine=null}}}}
fun approve(){engine?.let{e->e.pendingPairings().forEach{e.approve(it.id)}}}
fun stop(){session?.cancel()}
override fun onCleared(){stop();super.onCleared()}
}
