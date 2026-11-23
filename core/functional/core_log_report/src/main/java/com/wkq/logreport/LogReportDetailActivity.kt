package com.wkq.logreport

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.wkq.base.activity.BaseActivity
import com.wkq.logreport.databinding.ActivityLogReportDetailBinding

/** 用户主动检查封存报告后选择分享、邮件、另存或删除；不把分享器打开视为发送成功。 */
class LogReportDetailActivity : BaseActivity<ActivityLogReportDetailBinding>() {
    /** 进程重建后通过稳定报告ID重建详情。 */
    internal companion object { const val REPORT_ID = "log_report_id" }
    private val model by viewModels<LogReportDetailViewModel>()
    private var expanded = false
    private var attachment: ReportAttachment? = null

    /** 重建仅恢复摘要展开状态，附件由ViewModel和持久报告ID恢复。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        expanded = savedInstanceState?.getBoolean("summary_expanded") ?: false
        super.onCreate(savedInstanceState)
    }

    /** 明确绑定详情XML，避免业务特定资源依赖。 */
    override fun initViewBinding() { binding = ActivityLogReportDetailBinding.inflate(layoutInflater) }

    /** 所有对外动作由用户点击触发；删除使用明确确认。 */
    override fun initView() {
        binding.reportBack.setOnClickListener { finish() }
        binding.reportShare.setOnClickListener { share(false) }
        binding.reportEmail.setOnClickListener { share(true) }
        binding.reportSave.setOnClickListener {
            val file = attachment?.file ?: return@setOnClickListener
            val fragment = supportFragmentManager.findFragmentByTag("log_report_save") as? ReportSaveFragment
                ?: ReportSaveFragment().also { supportFragmentManager.beginTransaction().add(it, "log_report_save").commitNow() }
            fragment.save(file)
        }
        binding.reportPrepareAgain.setOnClickListener { model.load(intent.getStringExtra(REPORT_ID).orEmpty(), true) }
        binding.reportSummaryMore.setOnClickListener { expanded = !expanded; renderExpansion() }
        binding.reportDelete.setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.log_report_delete_title).setMessage(R.string.log_report_delete_message)
                .setPositiveButton(R.string.log_report_delete) { _, _ -> model.delete() }.setNegativeButton(android.R.string.cancel, null).show()
        }
        renderExpansion()
    }

    /** 异步整理结果随页面生命周期渲染，返回历史后仍能再次创建附件。 */
    override fun initData() {
        model.state.observe(this) { state ->
            if (state.deleted) { finish(); return@observe }
            attachment = state.attachment
            state.entry?.let { entry ->
                binding.reportDetailTime.text = reportTime(this, entry.time)
                binding.reportDetailState.setText(reportStateLabel(entry))
                binding.reportDetailId.text = getString(R.string.log_report_report_id, entry.id)
            }
            binding.reportDetailProgress.isVisible = state.loading
            binding.reportDetailError.isVisible = state.loading || state.error || state.deleteError
            binding.reportDetailError.setText(when { state.loading -> R.string.log_report_preparing; state.deleteError -> R.string.log_report_delete_error; else -> R.string.log_report_build_error })
            binding.reportPrepareAgain.isVisible = state.error
            binding.reportPrepareAgain.isEnabled = !state.loading
            binding.reportShare.isEnabled = state.attachment != null && !state.loading
            binding.reportShare.alpha = if (binding.reportShare.isEnabled) 1f else 0.5f
            binding.reportEmail.isEnabled = state.attachment != null && !state.loading
            binding.reportSave.isEnabled = state.attachment != null && !state.loading
            binding.reportDelete.isEnabled = state.entry != null && !state.loading
            binding.reportSummary.text = state.attachment?.summary.orEmpty()
            binding.reportSummaryMore.isVisible = state.attachment != null
            binding.reportAttachmentSize.isVisible = state.attachment != null
            state.attachment?.let { binding.reportAttachmentSize.text = getString(R.string.log_report_attachment_size, Formatter.formatFileSize(this, it.sizeBytes)) }
        }
        model.load(intent.getStringExtra(REPORT_ID).orEmpty())
    }

    /** 摘要可展开阅读全文，布局高度自然增长，不用缩小字号塞内容。 */
    private fun renderExpansion() {
        binding.reportSummary.maxLines = if (expanded) Int.MAX_VALUE else 12
        binding.reportSummary.ellipsize = if (expanded) null else TextUtils.TruncateAt.END
        binding.reportSummaryMore.setText(if (expanded) R.string.log_report_summary_less else R.string.log_report_summary_more)
    }

    /** 显式分享系统附件并授权临时读取；没有匹配接收端时保留记录供另存。 */
    private fun share(mail: Boolean) {
        val report = attachment ?: return
        runCatching { startActivity(Intent.createChooser(LogReport.shareIntent(report, mail), getString(if (mail) R.string.log_report_email else R.string.log_report_share))) }
            .onFailure { Toast.makeText(this, R.string.log_report_error, Toast.LENGTH_LONG).show() }
    }

    /** 配置变化后维持用户的摘要展开选择，不改变采集开关。 */
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putBoolean("summary_expanded", expanded) }
}
