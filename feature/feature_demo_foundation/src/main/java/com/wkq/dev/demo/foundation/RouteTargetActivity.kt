package com.wkq.dev.demo.foundation
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.foundation.databinding.DemoRouteTargetBinding
import com.wkq.router.annotation.Route

@Route(path = "/foundation/__route_target")
class RouteTargetActivity : DemoActivity<DemoRouteTargetBinding, RouteTargetViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_route_target_title), contentBinding.finish)
        contentBinding.finish.setOnClickListener { finish() }
        com.wkq.router.api.Router.inject(this); viewModel.report(value)
    }
    @com.wkq.router.annotation.Param(name="value") @JvmField var value: String = ""
}
class RouteTargetViewModel : DemoViewModel() {

}
