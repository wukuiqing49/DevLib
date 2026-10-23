package com.wkq.dev.demo.common

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.wkq.base.BaseViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class DemoResult(val text: String, val file: File? = null, val preview: File? = null)
data class DemoState(val busy: Boolean = false, val inputs: List<File> = emptyList(), val result: DemoResult? = null, val progress: Int = 0)

/** 只共享执行/文件选择机制，每个业务页创建自己的 VM 实例和仓储。 */
open class DemoViewModel : BaseViewModel() {
    private val mutable = MutableStateFlow(DemoState())
    val state = mutable.asStateFlow()
    protected var task: Job? = null
    fun execute(context: Context, block: suspend () -> DemoResult) {
        if (mutable.value.busy) return
        val app = context.applicationContext
        mutable.update { it.copy(busy = true, result = null, progress = 0) }
        showContent()
        task = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                ensureActive()
                mutable.update { it.copy(result = result, progress = 100) }
                showContent()
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(result = DemoResult(app.getString(R.string.demo_cancelled))) }
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(result = DemoResult(app.getString(R.string.demo_failed, error.javaClass.simpleName))) }
                // 不展示可能包含服务器凭据或 Token 的底层 exception message。
                showError(app.getString(R.string.demo_failed, error.javaClass.simpleName))
            } catch (error: UnsatisfiedLinkError) {
                mutable.update { it.copy(result = DemoResult(app.getString(R.string.demo_native_unavailable))) }
                showError(app.getString(R.string.demo_native_unavailable))
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun complete(result: DemoResult) { mutable.update { it.copy(result = result) }; showContent() }
    fun report(text: String) { mutable.update { it.copy(result = DemoResult(text)) }; showContent() }
    fun progress(value: Int) { mutable.update { it.copy(progress = value.coerceIn(0, 100)) } }
    fun cancel() { task?.cancel() }
    fun selected(): List<File> = state.value.inputs.also { require(it.isNotEmpty()) }
    fun importFiles(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        execute(context) {
            val files = DemoFiles.importFiles(context.applicationContext, uris)
            mutable.update { it.copy(inputs = files) }
            DemoResult(context.applicationContext.getString(R.string.demo_selected, files.size))
        }
    }
    fun sample(context: Context) = execute(context) {
        val file = DemoFiles.sample(context.applicationContext)
        mutable.update { it.copy(inputs = listOf(file)) }
        DemoResult(context.applicationContext.getString(R.string.demo_sample_ready), file, file)
    }
    fun reverseInputs() { mutable.update { it.copy(inputs = it.inputs.reversed()) } }
    protected fun input() = selected().first()
}
