package com.wkq.dev.demo.foundation

import android.content.Context
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.foundation.databinding.DemoProcessorBinding
import com.wkq.router.annotation.Route

/** KSP 路由处理器能力的宿主演示页，展示当前生成路由信息并提供带参跳转入口。 */
@Route(path = "/foundation/core_router_processor")
class ProcessorActivity : DemoActivity<DemoProcessorBinding, ProcessorViewModel>() {
    /** 绑定生成路由信息检查与带参导航按钮，复用公共结果展示。 */
    override fun initView() {
        bindDemo(
            contentBinding.result,
            getString(R.string.demo_processor_title),
            contentBinding.inspect,
            contentBinding.navigate,
        )
        contentBinding.inspect.setOnClickListener { viewModel.inspect(applicationContext) }
        contentBinding.navigate.setOnClickListener {
            com.wkq.router.api.Router.navigate(
                this,
                com.wkq.router.api.Router.build(contentBinding.path.text.toString())
                    .withString("value", contentBinding.value.text.toString()),
            )
        }
    }
}

/** KSP 路由处理器演示的状态持有者，通过宿主已生成的类型和注册表展示集成信息。 */
class ProcessorViewModel : DemoViewModel() {
    /** 在公共 IO 执行流程读取生成路径类名和已注册分组，仅证明当前宿主集成信息，不等同于插件独立验收。 */
    fun inspect(context: Context) =
        execute(context) {
            val generated = com.wkq.router.generated.RouterPaths_DevDemoFoundation::class.java
            DemoResult(
                "${generated.name}\n" +
                    com.wkq.router.api.RouteTable.groups.keys.sorted().joinToString("\n")
            )
        }
}
