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

    /** 明确绑定库内XML，系统边缘由Base统一承接。 */
    override fun initViewBinding() { binding = ActivityLogReportBinding.inflate(layoutInflater) }

    /** 标准返回只离开页面；采集继续，由其他页面的全局浮窗显示。 */
    override fun initView() {
        binding.reportBack.setOnClickListener { finish() }
        overview = LogReportOverviewAdapter(this, model::collect, model::retry, model::reload)
        history = LogReportHistoryAdapter(this) { id ->
            startActivity(Intent(this, LogReportDetailActivity::class.java).putExtra(LogReportDetailActivity.REPORT_ID, id))
        }
        binding.reportList.layoutManager = LinearLayoutManager(this)
        binding.reportList.adapter = ConcatAdapter(overview, history)
    }

    /** 观察生命周期内状态，崩溃恢复索引就绪后列表自动出现，不在页面线程扫描文件。 */
    override fun initData() {
        model.state.observe(this) { state -> overview.setData(listOf(state)); history.submit(state.reports) }
    }
}
