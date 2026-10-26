package com.wkq.dev.demo.foundation

import android.content.Context
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.foundation.databinding.DemoNetworkBinding
import com.wkq.net.config.NetConfig
import com.wkq.net.core.*
import com.wkq.router.annotation.Route

/** 网络基础能力的宿主页面，接受 HTTPS 地址并展示响应摘要。 */
@Route(path = "/foundation/core_network")
class NetworkActivity : DemoActivity<DemoNetworkBinding, NetworkViewModel>() {
    /** 绑定网络请求输入和执行按钮，复用统一结果与错误展示。 */
    override fun initView() {
        bindDemo(
            contentBinding.result,
            getString(R.string.demo_network_title),
            contentBinding.request,
        )
        contentBinding.request.setOnClickListener {
            viewModel.request(applicationContext, contentBinding.url.text.toString())
        }
    }
}

/** 将网络请求接入公共网络库，在 IO 读取有限响应并统一发布结果。 */
class NetworkViewModel : DemoViewModel() {
    /** 校验 HTTPS 主机后配置网络库并执行 GET，最多读取 12000 字符且关闭响应体，错误仅展示类型与码。 */
    fun request(context: Context, url: String) =
        execute(context) {
            val uri = java.net.URI(url)
            require(uri.scheme == "https" && uri.host != null)
            NetManager.init(NetConfig.Builder().setBaseUrl("https://${uri.host}/").build())
            val response = Net.rawCall(ApiRetrofit.create(DemoGetApi::class.java).get(url))
            DemoResult(
                when (response) {
                    is ApiResponse.Success ->
                        response.data
                            ?.use { body ->
                                body.charStream().use { reader ->
                                    val buffer = CharArray(12000)
                                    val count = reader.read(buffer)
                                    if (count > 0) String(buffer, 0, count) else ""
                                }
                            }
                            .orEmpty()
                    is ApiResponse.Error -> "${response.type} · ${response.code}"
                }
            )
        }
}
