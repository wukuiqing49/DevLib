package com.wkq.dev.demo.common

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.wkq.base.BaseViewModel
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 承载单次处理的提示、主产物、预览及多个产物；文件由宿主工作区管理。 */
data class DemoResult(
    val text: String,
    val file: File? = null,
    val preview: File? = null,
    val files: List<File> = emptyList(),
)

/** 页面可观察状态，保存执行标记、输入文件与显示名称、处理结果及百分比进度。 */
data class DemoState(
    val busy: Boolean = false,
    val inputs: List<File> = emptyList(),
    val names: Map<String, String> = emptyMap(),
    val result: DemoResult? = null,
    val progress: Int = 0,
)

/** 只共享执行/文件选择机制，每个业务页创建自己的 VM 实例和仓储。 */
open class DemoViewModel : BaseViewModel() {
    private val mutable = MutableStateFlow(DemoState())
    val state = mutable.asStateFlow()
    protected var task: Job? = null

    private var workspaceTitle = "DevLib"
    private var workspaceRoute = ""
    private var receivedAssets = false

    /** 设置当前工具的显示标题和路由，用于后续工作区操作记录与输入类型校验。 */
    fun configureWorkspace(title: String, route: String) {
        workspaceTitle = title
        workspaceRoute = route
    }

    /** 允许重新选择工作区素材，并交给统一接收流程校验和载入。 */
    fun selectWorkspaceAssets(context: Context, ids: List<String>) {
        receivedAssets = false
        receiveAssets(context, ids)
    }

    /** 首次接收最多 20 个工作区资产，任何缺失或类型不符都拒绝整批并清空旧输入；成功后才标记已接收，失败可重试。 */
    fun receiveAssets(context: Context, ids: List<String>) {
        if (receivedAssets || ids.isEmpty() || state.value.busy) return
        val requested = ids.toList()
        mutable.update { it.copy(inputs = emptyList(), names = emptyMap()) }
        execute(context, record = false) {
            val store = WorkspaceStore.get(context)
            val assets = resolveWorkspaceAssets(requested, workspaceRoute, store::find)
            mutable.update {
                it.copy(
                    inputs = assets.map { asset -> asset.file },
                    names = assets.associate { asset -> asset.path to asset.name },
                )
            }
            receivedAssets = true
            DemoResult(context.getString(R.string.demo_selected, assets.size))
        }
    }

    /** 在 ViewModel 协程内串行执行前台操作，耗时处理使用 IO；可记录历史与产物，取消时传播取消并补写最终状态，历史写入失败保留产物并提示，始终解除忙碌。 */
    fun execute(context: Context, record: Boolean = true, block: suspend () -> DemoResult) {
        if (mutable.value.busy) return
        val app = context.applicationContext
        mutable.update { it.copy(busy = true, result = null, progress = 0) }
        showContent()
        task =
            viewModelScope.launch {
                var historyId: String? = null
                var historyStatus = "failed"
                var outputIds: List<String> = emptyList()
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            val store = WorkspaceStore.get(app)
                            if (record)
                                historyId =
                                    store.start(workspaceTitle, workspaceRoute, state.value.inputs)
                            val value = block()
                            ensureActive()
                            if (record) {
                                val outputs =
                                    (listOfNotNull(value.file) + value.files).distinctBy {
                                        it.canonicalPath
                                    }
                                outputIds =
                                    outputs.mapNotNull { file ->
                                        store
                                            .register(
                                                file,
                                                if (outputs.size > 1)
                                                    "$workspaceTitle · ${file.name}"
                                                else
                                                    "$workspaceTitle · ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}.${file.extension}",
                                                "output",
                                                workspaceTitle,
                                            )
                                            ?.id
                                    }
                            }
                            value
                        }
                    ensureActive()
                    mutable.update { it.copy(result = result, progress = 100) }
                    historyStatus = "completed"
                    showContent()
                } catch (timeout: TimeoutCancellationException) {
                    report(app.getString(R.string.demo_error_timeout))
                } catch (cancelled: CancellationException) {
                    historyStatus = "cancelled"
                    mutable.update {
                        it.copy(result = DemoResult(app.getString(R.string.demo_cancelled)))
                    }
                    throw cancelled
                } catch (error: Exception) {
                    if (demoErrorKind(error) == DemoErrorKind.CANCELLED) historyStatus = "cancelled"
                    val text =
                        if (error is DemoInputException) error.displayMessage
                        else
                            app.getString(
                                when (demoErrorKind(error)) {
                                    DemoErrorKind.INPUT -> R.string.demo_error_input
                                    DemoErrorKind.PERMISSION -> R.string.demo_error_permission
                                    DemoErrorKind.NOT_FOUND -> R.string.demo_error_not_found
                                    DemoErrorKind.TIMEOUT -> R.string.demo_error_timeout
                                    DemoErrorKind.CERTIFICATE -> R.string.demo_error_certificate
                                    DemoErrorKind.NETWORK -> R.string.demo_error_network
                                    DemoErrorKind.BUDGET -> R.string.demo_error_budget
                                    DemoErrorKind.CONFLICT -> R.string.demo_error_conflict
                                    DemoErrorKind.FORMAT -> R.string.demo_error_format
                                    DemoErrorKind.CANCELLED -> R.string.demo_cancelled
                                    DemoErrorKind.IO -> R.string.demo_error_io
                                    DemoErrorKind.UNKNOWN -> R.string.demo_error_unknown
                                }
                            )
                    mutable.update { it.copy(result = DemoResult(text)) }
                    showError(text)
                } catch (error: UnsatisfiedLinkError) {
                    mutable.update {
                        it.copy(
                            result = DemoResult(app.getString(R.string.demo_native_unavailable))
                        )
                    }
                    showError(app.getString(R.string.demo_native_unavailable))
                } finally {
                    finishDemoOperation(
                        persist = {
                            withContext(Dispatchers.IO) {
                                historyId?.let {
                                    WorkspaceStore.get(app).finish(it, historyStatus, outputIds)
                                }
                            }
                        },
                        onFailure = {
                            val warning = app.getString(R.string.demo_history_failed)
                            mutable.update { current ->
                                val result = current.result ?: DemoResult("")
                                current.copy(result = result.copy(
                                    text = listOf(result.text, warning).filter { it.isNotBlank() }
                                        .joinToString("\n\n")
                                ))
                            }
                        },
                        reset = { mutable.update { it.copy(busy = false) } },
                    )
                }
            }
    }

    /** 发布完整处理结果并恢复内容态，不创建新的执行任务或历史记录。 */
    fun complete(result: DemoResult) {
        mutable.update { it.copy(result = result) }
        showContent()
    }

    /** 发布已资源化的提示文本并显示内容态，不直接展示底层异常消息。 */
    fun report(text: String) {
        mutable.update { it.copy(result = DemoResult(text)) }
        showContent()
    }

    /** 将进度限制在 0 到 100 后发布给页面。 */
    fun progress(value: Int) {
        mutable.update { it.copy(progress = value.coerceIn(0, 100)) }
    }

    /** 取消当前前台协程任务，清理与历史收尾由执行流程负责。 */
    fun cancel() {
        task?.cancel()
    }

    /** 返回当前输入文件；没有素材时抛出参数错误，由统一错误映射显示提示。 */
    fun selected(): List<File> = state.value.inputs.also { require(it.isNotEmpty()) }

    /** 在 IO 执行文件复制并更新输入列表和显示名，导入本身不生成处理历史。 */
    fun importFiles(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        execute(context, record = false) {
            val files = DemoFiles.importFiles(context.applicationContext, uris)
            val names =
                WorkspaceStore.get(context).snapshot().assets.associate { it.path to it.name }
            mutable.update { it.copy(inputs = files, names = names) }
            DemoResult(context.applicationContext.getString(R.string.demo_selected, files.size))
        }
    }

    /** 在 IO 生成并登记示例图片，设置为当前输入，同时提供可预览的结果。 */
    fun sample(context: Context) =
        execute(context, record = false) {
            val file = DemoFiles.sample(context.applicationContext)
            WorkspaceStore.get(context)
                .register(file, context.getString(R.string.demo_sample), "input", workspaceTitle)
            mutable.update {
                it.copy(
                    inputs = listOf(file),
                    names = mapOf(file.canonicalPath to context.getString(R.string.demo_sample)),
                )
            }
            DemoResult(context.applicationContext.getString(R.string.demo_sample_ready), file, file)
        }

    /** 非忙碌时按相对偏移交换有效索引处的素材，供文档页序调整使用。 */
    fun moveInput(index: Int, direction: Int) {
        if (state.value.busy) return
        mutable.update { current ->
            val items = current.inputs.toMutableList()
            val target = index + direction
            if (index in items.indices && target in items.indices)
                java.util.Collections.swap(items, index, target)
            current.copy(inputs = items)
        }
    }

    /** 非忙碌时移除指定位置的输入引用，不删除工作区文件。 */
    fun removeInput(index: Int) {
        if (!state.value.busy)
            mutable.update { it.copy(inputs = it.inputs.filterIndexed { i, _ -> i != index }) }
    }

    /** 反转当前输入顺序，用于批量处理或文档页序调整。 */
    fun reverseInputs() {
        mutable.update { it.copy(inputs = it.inputs.reversed()) }
    }

    /** 获取首个已选素材；没有输入时沿用统一参数错误处理。 */
    protected fun input() = selected().first()
}
