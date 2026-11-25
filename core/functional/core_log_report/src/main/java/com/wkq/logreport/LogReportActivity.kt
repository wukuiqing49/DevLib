package com.wkq.logreport

import android.content.Intent
import androidx.activity.viewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.wkq.base.activity.BaseActivity
import com.wkq.logreport.databinding.ActivityLogReportBinding

/** 日志管理的固定入口：介绍、开关和持久历史，不依赖宿主feature导航。 */
class LogReportActivity : BaseActivity<ActivityLogReportBinding>() {
    private val model by viewModels<LogReportViewModel>()
    private lateinit var overview: LogReportOverviewAdapter
    private lateinit var history: LogReportHistoryAdapter
    private lateinit var guides: LogReportGuides

    /** 明确绑定库内XML，系统边缘由Base统一承接。 */
    override fun initViewBinding() { binding = ActivityLogReportBinding.inflate(layoutInflater) }

    /** 标准返回只离开页面；采集继续，由其他页面的全局浮窗显示。 */
    override fun initView() {
        guides = LogReportGuides(this)
        binding.reportBack.setOnClickListener { finish() }
        binding.reportHelp.setOnClickListener { model.state.value?.let { guides.overview(it, binding.reportList, true) } }
        overview = LogReportOverviewAdapter(this, model::collect, model::retry, model::reload)
        history = LogReportHistoryAdapter(this) { id ->
            startActivity(Intent(this, LogReportDetailActivity::class.java).putExtra(LogReportDetailActivity.REPORT_ID, id))
        }
        binding.reportList.layoutManager = LinearLayoutManager(this)
        binding.reportList.adapter = ConcatAdapter(overview, history)
    }

    /** 观察生命周期内状态，崩溃恢复索引就绪后列表自动出现，不在页面线程扫描文件。 */
    override fun initData() {
        model.state.observe(this) { state ->
            overview.setData(listOf(state))
            history.submit(state.reports)
            binding.reportHelp.isEnabled = LogReportGuidePolicy.overview(state) != null
            guides.overview(state, binding.reportList)
        }
    }

    /** 返回日志页时按当前采集状态说明下一步，不重复已展示过的教程。 */
    override fun onResume() {
        super.onResume()
        guides.resume { model.state.value?.let { guides.overview(it, binding.reportList) } }
    }

    /** 离开页面移除提示，采集开关与浮动记录状态继续由日志引擎管理。 */
    override fun onPause() { guides.pause(); super.onPause() }
}
