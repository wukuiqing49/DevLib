package com.wkq.logreport

import android.view.View
import com.wkq.base.activity.BaseActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import com.wkq.guide.FeatureGuide
import com.wkq.guide.GuideHistory
import com.wkq.guide.GuideOptions
import com.wkq.guide.GuideRecord
import com.wkq.guide.GuideResult
import com.wkq.guide.GuideStep

/** 日志页按真实稳定状态选择教程；读取失败或整理期间不遮挡恢复入口。 */
internal object LogReportGuidePolicy {
    /** 未记录、正在记录和已有报告分别说明下一项实际操作。 */
    fun overview(state: ReportPageState): String? = when {
        state.historyLoading || state.historyError || state.operationError -> null
        state.phase == ReportPhase.RECORDING -> "stop"
        state.phase != ReportPhase.IDLE -> null
        state.reports.isNotEmpty() -> "history"
        else -> "start"
    }

    /** 仅有完整附件时介绍分享，失败或删除期间不展示可发送的暗示。 */
    fun detail(state: ReportDetailState): Boolean = state.attachment != null && !state.loading &&
        !state.error && !state.deleted && !state.deleteError
}

/** 日志专属展示编排；记录阅读而不改变采集、生成或发送状态，不持有到页面生命周期之外。 */
internal class LogReportGuides(private val activity: BaseActivity<*>) {
    private val history = GuideHistory(activity, "log_report")
    private var session: FeatureGuide? = null
    private var contextKey: String? = null
    private var generation = 0
    private var handledEntry = false

    /** 一次前台访问最多自动展示一组；持久阅读记录阻止旋转和下次进入重复打扰。 */
    fun resume(render: () -> Unit) {
        handledEntry = false
        val ticket = generation
        activity.window.decorView.post {
            if (ticket == generation && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) render()
        }
    }

    /** 离开立即撤销未执行的展示与遮罩，不终止真实日志记录。 */
    fun pause() { cancel(); handledEntry = true }

    /** 根据采集状态高亮真实开关、返回入口或已生成报告，手动入口可重看。 */
    fun overview(state: ReportPageState, list: RecyclerView, manual: Boolean = false) {
        val available = LogReportGuidePolicy.overview(state)
        val key = available?.let { "overview:${state.phase}" }
        invalidate(key)
        if (available == null) return
        val kind = if (manual && state.phase == ReportPhase.IDLE) "start" else available
        val steps = when (kind) {
            "start" -> listOf(
                step("enable", R.id.report_collect_switch, R.string.log_guide_start_title, R.string.log_guide_start_body),
                step("reproduce", R.id.report_back, R.string.log_guide_reproduce_title, R.string.log_guide_reproduce_body),
            )
            "stop" -> listOf(step("stop", R.id.report_collect_switch, R.string.log_guide_stop_title, R.string.log_guide_stop_body))
            else -> listOf(step("history", R.id.report_row, R.string.log_guide_history_title, R.string.log_guide_history_body))
        }
        show(checkNotNull(key), kind, steps, manual) { list.scrollToPosition(if (kind == "history") 1 else 0) }
    }

    /** 完整附件准备成功后说明分享和另存；阅读完成从不等同外部接收成功。 */
    fun detail(state: ReportDetailState, manual: Boolean = false) {
        val ready = LogReportGuidePolicy.detail(state)
        invalidate(if (ready) "detail" else null)
        if (!ready) return
        show("detail", "share", listOf(
            step("share", R.id.report_share, R.string.log_guide_share_title, R.string.log_guide_share_body),
            step("save", R.id.report_save, R.string.log_guide_save_title, R.string.log_guide_save_body),
        ), manual) {
            activity.findViewById<View>(R.id.report_share)?.let { target ->
                target.requestRectangleOnScreen(android.graphics.Rect(0, 0, target.width, target.height), true)
            }
        }
    }

    /** 每次解析当前真实控件，列表重绑后不复用过期 View；下一步不触发目标点击。 */
    private fun step(id: String, target: Int, title: Int, body: Int): GuideStep = GuideStep(id,
        { activity.findViewById(target) }, activity.getString(title), activity.getString(body))

    /** 状态跨阶段时撤掉旧说明，防止仍提示开启已在运行的采集。 */
    private fun invalidate(key: String?) { if (contextKey != null && contextKey != key) cancel() }

    /** 取消待展示回调和当前会话，代次隔离防止旧结束回调覆盖新会话。 */
    private fun cancel() {
        generation++
        session?.dismiss()
        session = null
        contextKey = null
    }

    /** 只在前台且布局可用时展示；自动教程按版本记一次，手动重看不重置其他记录。 */
    private fun show(key: String, id: String, steps: List<GuideStep>, manual: Boolean, prepare: () -> Unit) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!manual && (handledEntry || contextKey != null || history.records(id, 1).isNotEmpty())) return
        cancel()
        handledEntry = true
        contextKey = key
        val ticket = generation
        activity.window.decorView.doOnLayout {
            if (ticket != generation || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@doOnLayout
            prepare()
            activity.window.decorView.post {
                if (ticket != generation || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@post
                session = FeatureGuide.show(activity.window, activity, steps, GuideOptions(
                    onStep = { _, _ -> history.mark(id, 1, GuideRecord.SHOWN) },
                    onFinished = { result ->
                        if (result == GuideResult.COMPLETED) history.mark(id, 1, GuideRecord.READ)
                        if (result == GuideResult.SKIPPED || result == GuideResult.BACK) history.mark(id, 1, GuideRecord.SKIPPED)
                        if (ticket == generation) { session = null; contextKey = null }
                    },
                ), activity.onBackPressedDispatcher)
            }
        }
    }
}
