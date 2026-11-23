package com.wkq.logreport

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.wkq.base.BaseViewModel

/** 主页共享全局采集快照，不持有Activity；返回和旋转不会改变开关。 */
internal class LogReportViewModel : BaseViewModel() {
    val state: LiveData<ReportPageState> = LogReport.pageState
    /** 用户明确切换后才改变采集，渲染开关本身不触发操作。 */
    fun collect(enabled: Boolean) { LogReport.setCollecting(enabled) }
    /** 重试同一份未完成整理，不新建会话。 */
    fun retry() { LogReport.retryFinalization() }
    /** 历史索引读取失败后由用户重试，不删除原始文件。 */
    fun reload() { LogReport.reloadHistory() }
}

/** 详情只保存ID与附件结果，所有磁盘操作交给日志IO队列。 */
internal class LogReportDetailViewModel : BaseViewModel() {
    private val mutable = MutableLiveData(ReportDetailState())
    val state: LiveData<ReportDetailState> = mutable
    private var reportId: String? = null
    private var generation = 0

    /** 同一实例仅加载一次，失败时允许显式重试。 */
    fun load(id: String, retry: Boolean = false) {
        if (id == reportId && !retry) return
        if (mutable.value?.loading == true) return
        reportId = id
        val ticket = ++generation
        val entry = LogReport.pageState.value?.reports?.firstOrNull { it.id == id }
        mutable.value = ReportDetailState(entry = entry, loading = true)
        LogReport.prepareAttachment(id) { result ->
            if (ticket != generation) return@prepareAttachment
            mutable.value = ReportDetailState(entry = LogReport.pageState.value?.reports?.firstOrNull { it.id == id } ?: entry,
                attachment = result.getOrNull(), error = result.isFailure)
        }
    }

    /** 用户确认后删除封存记录，失败仍保留详情和重试机会。 */
    fun delete() {
        val id = reportId ?: return
        val before = mutable.value ?: return
        if (before.loading) return
        mutable.value = before.copy(loading = true)
        val ticket = ++generation
        LogReport.deleteReport(id) { success ->
            if (ticket == generation) mutable.value = before.copy(loading = false, deleted = success, deleteError = !success)
        }
    }

    /** 离开详情后忽略晚到结果，后台任务不回调已销毁页面。 */
    override fun onCleared() { generation++; super.onCleared() }
}
