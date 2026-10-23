package com.wkq.dev.demo.foundation
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.foundation.databinding.DemoNetworkBinding
import com.wkq.router.annotation.Route
import com.wkq.net.core.*
import com.wkq.net.config.NetConfig
import retrofit2.http.GET
import retrofit2.http.Url
import okhttp3.ResponseBody
@Route(path = "/foundation/core_network")
class NetworkActivity : DemoActivity<DemoNetworkBinding, NetworkViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_network_title), contentBinding.request)
        contentBinding.request.setOnClickListener { viewModel.request(applicationContext,contentBinding.url.text.toString()) }

    }

}
class NetworkViewModel : DemoViewModel() {
    fun request(context:Context,url:String)=execute(context){
val uri=java.net.URI(url);require(uri.scheme=="https" && uri.host!=null)
NetManager.init(NetConfig.Builder().setBaseUrl("https://${uri.host}/").build())
val response=Net.rawCall(ApiRetrofit.create(DemoGetApi::class.java).get(url)); DemoResult(when(response){is ApiResponse.Success->response.data?.use{body->body.charStream().use{reader->val buffer=CharArray(12000);val count=reader.read(buffer);if(count>0)String(buffer,0,count)else ""}}.orEmpty();is ApiResponse.Error->"${response.type} · ${response.code}"})
}
}
