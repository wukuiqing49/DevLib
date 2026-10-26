package com.wkq.dev.demo.files

import android.content.Context
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.lxj.xpopup.XPopup
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.files.databinding.DemoDropBinding
import com.wkq.droplan.drop.*
import com.wkq.router.annotation.Route
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/** 局域网分享宿主页，负责权限入口、监听地址选择和逐个配对确认，离开前台即停止分享。 */
@Route(path = "/files/lib_drop")
class DropActivity : DemoActivity<DemoDropBinding, DropViewModel>() {
    /** 绑定素材、分享及配对操作，在 STARTED 期间联动分享状态与按钮可用性。 */
    override fun initView() {
        val b = contentBinding
        bindDemo(b.result, getString(R.string.demo_drop_title), b.approve, b.reject, b.stop)
        b.choose.setOnClickListener { pickFiles("*/*") }
        b.sample.setOnClickListener { viewModel.sample(applicationContext) }
        b.start.setOnClickListener { withLocalNetworkPermission { startSharing() } }
        b.approve.setOnClickListener { choosePairing(true) }
        b.reject.setOnClickListener { choosePairing(false) }
        b.stop.setOnClickListener {
            viewModel.stop()
            viewModel.report(getString(R.string.demo_drop_stopped))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.sharing, viewModel.state) { sharing, state ->
                        sharing to state.busy
                    }
                    .collect { (sharing, busy) ->
                        b.choose.isEnabled = !sharing && !busy
                        b.sample.isEnabled = !sharing && !busy
                        b.start.isEnabled = !sharing && !busy
                    }
            }
        }
    }

    /** 列出待配对请求，用户选择并二次确认后只批准或拒绝该请求。 */
    private fun choosePairing(approve: Boolean) {
        val pending = viewModel.pending()
        if (pending.isEmpty()) {
            message(getString(R.string.demo_drop_no_pending))
            return
        }
        XPopup.Builder(this)
            .asBottomList(
                getString(R.string.demo_drop_choose_pairing),
                pending.map { getString(R.string.demo_drop_pairing, it.id) }.toTypedArray(),
            ) { index, _ ->
                val id = pending[index].id
                confirm(
                    getString(
                        if (approve) R.string.demo_drop_confirm_approve
                        else R.string.demo_drop_confirm_reject,
                        id,
                    )
                ) {
                    val accepted = viewModel.decide(id, approve)
                    viewModel.report(
                        getString(
                            if (accepted) R.string.demo_drop_decided else R.string.demo_drop_expired
                        )
                    )
                }
            }
            .show()
    }

    /** 校验已选素材，在 IO 发现可信局域网地址，再由用户选择服务监听地址。 */
    private fun startSharing() {
        if (viewModel.state.value.inputs.isEmpty()) {
            message(getString(R.string.demo_drop_choose_first))
            return
        }
        lifecycleScope.launch {
            val addresses =
                withContext(Dispatchers.IO) {
                    LocalAddressDiscovery.candidates(applicationContext)
                        .filter { it.recognizedLan }
                        .map { it.address }
                }
            if (addresses.isEmpty()) {
                message(getString(R.string.demo_drop_no_address))
                return@launch
            }
            XPopup.Builder(this@DropActivity)
                .asBottomList(getString(R.string.demo_drop_start), addresses.toTypedArray()) {
                    index,
                    _ ->
                    viewModel.start(applicationContext, addresses[index])
                }
                .show()
        }
    }

    /** 页面不再可见时取消分享会话，随后执行父类生命周期处理。 */
    override fun onStop() {
        viewModel.stop()
        super.onStop()
    }
}

/** 管理前台分享协程与引擎引用，负责状态轮询和服务、文件源的最终释放。 */
class DropViewModel : DemoViewModel() {
    private var session: Job? = null
    @Volatile private var engine: ShareEngine? = null
    private val mutableSharing = MutableStateFlow(false)
    val sharing = mutableSharing.asStateFlow()

    /** 以已选素材启动单个 IO 分享会话，每秒更新状态；取消或失败时关闭服务和分享引擎。 */
    fun start(context: Context, address: String) {
        if (session?.isCompleted == false || state.value.busy) return
        val files = state.value.inputs
        if (files.isEmpty()) return
        val app = context.applicationContext
        mutableSharing.value = true
        session =
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    var server: DropServer? = null
                    var sharing: ShareEngine? = null
                    try {
                        sharing =
                            ShareEngine(
                                files.map {
                                    UriShareSource.create(
                                        app.contentResolver,
                                        FileProvider.getUriForFile(
                                            app,
                                            "${app.packageName}.fileprovider",
                                            it,
                                        ),
                                    )
                                }
                            )
                        engine = sharing
                        server = DropServer(address, 0, sharing, DemoReceiverAssets.provider(app))
                        server.start()
                        while (isActive) {
                            val text =
                                app.getString(
                                    R.string.demo_drop_status,
                                    server.entryUrl,
                                    sharing.pendingPairings().size,
                                    sharing.receivers().size,
                                ) +
                                    sharing.senderTasks().joinToString("\n", prefix = "\n") { task
                                        ->
                                        app.getString(
                                            R.string.demo_drop_task,
                                            task.name,
                                            task.download.toString(),
                                        )
                                    }
                            withContext(Dispatchers.Main) { report(text) }
                            delay(1000)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            report(app.getString(R.string.demo_drop_failed))
                        }
                    } finally {
                        server?.close()
                        sharing?.close()
                        engine = null
                        mutableSharing.value = false
                    }
                }
            }
    }

    /** 获取当前分享引擎的待配对快照，无会话时返回空列表。 */
    fun pending(): List<PairingSnapshot> = engine?.pendingPairings().orEmpty()

    /** 一次只处理 UI 明确选中的请求；过期或已关闭会话不会授权其他请求。 */
    fun decide(id: String, approve: Boolean): Boolean =
        runCatching {
                val active = engine ?: return false
                if (active.pendingPairings().none { it.id == id }) return false
                if (approve) active.approve(id)
                else {
                    active.reject(id)
                    true
                }
            }
            .getOrDefault(false)

    /** 请求取消分享会话，实际资源关闭由会话 finally 块完成。 */
    fun stop() {
        session?.cancel()
    }

    /** ViewModel 销毁时取消分享会话，再执行基类清理。 */
    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
