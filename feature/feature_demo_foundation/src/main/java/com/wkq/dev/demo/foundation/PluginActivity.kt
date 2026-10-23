package com.wkq.dev.demo.foundation
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.foundation.databinding.DemoPluginBinding
import com.wkq.router.annotation.Route

@Route(path = "/foundation/core_router_gradle_plugin")
class PluginActivity : DemoActivity<DemoPluginBinding, PluginViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_plugin_title), contentBinding.inspect, contentBinding.navigate)
        contentBinding.inspect.setOnClickListener { viewModel.inspect(applicationContext) }
contentBinding.navigate.setOnClickListener { com.wkq.router.api.Router.navigate(this,com.wkq.router.api.Router.build(contentBinding.path.text.toString()).withString("value",contentBinding.value.text.toString())) }

    }

}
class PluginViewModel : DemoViewModel() {
    fun inspect(context:Context)=execute(context){ val generated=com.wkq.router.generated.RouterPaths_DevDemoFoundation::class.java;DemoResult("${generated.name}\n"+com.wkq.router.api.RouteTable.groups.keys.sorted().joinToString("\n")) }
}
