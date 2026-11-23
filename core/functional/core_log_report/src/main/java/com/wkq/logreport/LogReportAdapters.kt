package com.wkq.logreport

import android.content.Context
import android.text.format.Formatter
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import com.wkq.base.adapter.BaseRecyclerViewAdapter
import com.wkq.logreport.databinding.ItemLogReportBinding
import com.wkq.logreport.databinding.LogReportOverviewBinding
import java.text.DateFormat
import java.util.Date

/** 只根据已经获得的证据标记崩溃，中断不直接等同崩溃。 */
internal fun reportStateLabel(entry: ReportEntry): Int = when {
    entry.crashed -> R.string.log_report_state_crashed
    entry.interrupted -> R.string.log_report_state_interrupted
    else -> R.string.log_report_state_finished
}

/** 时间使用当前页面语言，避免与应用内语言设置不一致。 */
internal fun reportTime(context: Context, time: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
    context.resources.configuration.locales[0]).format(Date(time))

/** 说明和开关作为列表头，页面整体滚动而不嵌套历史滚动容器。 */
internal class LogReportOverviewAdapter(context: Context, private val collect: (Boolean) -> Unit,
    private val retry: () -> Unit, private val reload: () -> Unit) :
    BaseRecyclerViewAdapter<LogReportOverviewBinding, ReportPageState>(context, LogReportOverviewBinding::inflate) {
    /** 根据真实采集阶段绑定说明，监听器在程序修改开关期间断开。 */
    override fun convert(binding: LogReportOverviewBinding, item: ReportPageState, position: Int) {
        val busy = item.phase == ReportPhase.STARTING || item.phase == ReportPhase.FINALIZING
        binding.reportCollectSwitch.setOnCheckedChangeListener(null)
        binding.reportCollectSwitch.isChecked = item.phase == ReportPhase.RECORDING || item.phase == ReportPhase.STARTING
        binding.reportCollectSwitch.isEnabled = !busy && item.phase != ReportPhase.FAILED && !item.historyLoading && !item.historyError
        binding.reportCollectSwitch.setOnCheckedChangeListener { _, enabled -> collect(enabled) }
        binding.reportStatus.setText(when (item.phase) {
            ReportPhase.STARTING -> R.string.log_report_starting
            ReportPhase.RECORDING -> R.string.log_report_recording
            ReportPhase.FINALIZING -> R.string.log_report_preparing
            ReportPhase.FAILED -> R.string.log_report_finish_failed
            else -> if (item.operationError) R.string.log_report_error else R.string.log_report_off
        })
        binding.reportStatusHint.setText(if (item.phase == ReportPhase.RECORDING) R.string.log_report_on_hint else R.string.log_report_off_hint)
        binding.reportRetry.isVisible = item.phase == ReportPhase.FAILED
        binding.reportRetry.setOnClickListener { retry() }
        binding.reportRecoveryNotice.isVisible = item.reports.any { it.interrupted }
        binding.reportEmpty.isVisible = item.historyLoading || item.historyError || item.reports.isEmpty()
        binding.reportEmpty.setText(when {
            item.historyLoading -> R.string.log_report_loading_history
            item.historyError -> R.string.log_report_history_error
            else -> R.string.log_report_empty
        })
        binding.reportReload.isVisible = item.historyError
        binding.reportReload.setOnClickListener { reload() }
    }
}

/** 历史采用稳定报告身份和差量更新，换状态不重建用户滚动位置。 */
internal class LogReportHistoryAdapter(context: Context, private val open: (String) -> Unit) :
    BaseRecyclerViewAdapter<ItemLogReportBinding, ReportEntry>(context, ItemLogReportBinding::inflate) {
    /** 只刷新新增、删除或元数据变化的封存项。 */
    fun submit(values: List<ReportEntry>) {
        val previous = dataList.toList()
        val changes = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            /** 旧快照条数。 */
            override fun getOldListSize() = previous.size
            /** 新快照条数。 */
            override fun getNewListSize() = values.size
            /** 文件夹ID是持久身份。 */
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = previous[oldItemPosition].id == values[newItemPosition].id
            /** 大小、时间或证据状态变化时更新行。 */
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = previous[oldItemPosition] == values[newItemPosition]
        })
        dataList.clear(); dataList.addAll(values); changes.dispatchUpdatesTo(this)
    }

    /** 行只显示时间、状态和本机大小，点开完整详情，不隐藏崩溃后的历史入口。 */
    override fun convert(binding: ItemLogReportBinding, item: ReportEntry, position: Int) {
        val date = reportTime(context, item.time)
        val label = context.getString(reportStateLabel(item))
        binding.reportTime.text = date
        binding.reportMeta.text = context.getString(R.string.log_report_record_meta, label, Formatter.formatFileSize(context, item.sizeBytes))
        binding.reportRow.contentDescription = context.getString(R.string.log_report_record_open, date, label)
        binding.reportRow.setOnClickListener { open(item.id) }
    }
}
