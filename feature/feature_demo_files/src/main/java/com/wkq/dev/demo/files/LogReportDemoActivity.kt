package com.wkq.dev.demo.files

import com.wkq.dev.demo.common.DemoActivity
import com.wkq.dev.demo.common.DemoViewModel
import com.wkq.dev.demo.files.databinding.DemoLogReportBinding
import com.wkq.logreport.LogReport
import com.wkq.router.annotation.Route

/** 复用工作台容器打开库自带管理页，不自动启动收集或生成模拟事件。 */
@Route(path = "/files/core_log_report")
class LogReportDemoActivity : DemoActivity<DemoLogReportBinding, LogReportDemoViewModel>() {
    /** 沿用共享返回和 Insets，点击后进入真实日志管理流程。 */
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_log_report_title), contentBinding.open)
        contentBinding.open.setOnClickListener { LogReport.show(this) }
    }
}

/** 沿用工作台状态与生命周期封装，采集状态由日志库统一维护。 */
class LogReportDemoViewModel : DemoViewModel()
