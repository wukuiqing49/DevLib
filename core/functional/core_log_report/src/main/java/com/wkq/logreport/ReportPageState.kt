package com.wkq.logreport

import java.io.File

/** 全局采集状态与界面保持一致，准备和整理期间禁止重复操作。 */
internal enum class ReportPhase { IDLE, STARTING, RECORDING, FINALIZING, FAILED }

/** IO线程计算的封存记录索引，页面不读取目录或日志正文。 */
internal data class ReportEntry(val id: String, val time: Long, val sizeBytes: Long, val interrupted: Boolean, val crashed: Boolean)

/** 日志主页只读快照，历史读取与采集失败分别展示。 */
internal data class ReportPageState(val phase: ReportPhase = ReportPhase.IDLE, val historyLoading: Boolean = true,
    val historyError: Boolean = false, val operationError: Boolean = false, val reports: List<ReportEntry> = emptyList())

/** 在IO队列完整生成的附件，摘要和大小同属一次生成结果。 */
internal data class ReportAttachment(val id: String, val file: File, val summary: String, val sizeBytes: Long)

/** 详情准备与删除状态不依赖Activity，旋转不会重复整理或丢失已生成附件。 */
internal data class ReportDetailState(val entry: ReportEntry? = null, val attachment: ReportAttachment? = null,
    val loading: Boolean = false, val error: Boolean = false, val deleted: Boolean = false, val deleteError: Boolean = false)
