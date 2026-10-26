package com.wkq.dev.demo.foundation

import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.foundation.databinding.DemoRouteTargetBinding
import com.wkq.router.annotation.Route

/** 路由演示的目标页，用于验证参数注入和页面返回流程。 */
@Route(path = "/foundation/__route_target")
class RouteTargetActivity : DemoActivity<DemoRouteTargetBinding, RouteTargetViewModel>() {
    /** 绑定关闭按钮，调用 Router 注入参数后在结果区显示收到的值。 */
    override fun initView() {
        bindDemo(
            contentBinding.result,
            getString(R.string.demo_route_target_title),
            contentBinding.finish,
        )
        contentBinding.finish.setOnClickListener { finish() }
        com.wkq.router.api.Router.inject(this)
        viewModel.report(value)
    }

    @com.wkq.router.annotation.Param(name = "value") @JvmField var value: String = ""
}

/** 保存路由目标页接收的参数展示结果，复用通用页面状态。 */
class RouteTargetViewModel : DemoViewModel() {}
