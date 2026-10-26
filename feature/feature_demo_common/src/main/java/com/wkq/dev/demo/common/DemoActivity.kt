package com.wkq.dev.demo.common

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.lxj.xpopup.XPopup
import com.wkq.base.BaseUiEvent
import com.wkq.base.activity.BaseVMTitleActivity
import com.wkq.base.dialog.DialogKit
import com.wkq.dev.demo.common.databinding.DemoOutputBinding
import com.wkq.router.api.Router
import com.wkq.util.DocumentPickerHelper
import com.wkq.util.PhotoPickerHelper
import com.wkq.util.ShareUtil
import com.wkq.util.coil.ImageLoaderUtil
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 复用 BaseVMTitleActivity 的宿主工具页基类，统一权限说明、素材选择、结果操作与生命周期订阅。 */
abstract class DemoActivity<VB : ViewBinding, VM : DemoViewModel> : BaseVMTitleActivity<VB, VM>() {
    // Permission belongs to the host; engines remain independent of permission UI.
    private var pendingLocalNetworkAction: (() -> Unit)? = null
    private val localNetworkPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingLocalNetworkAction
            pendingLocalNetworkAction = null
            if (granted) {
                if (action != null) action() else message(getString(R.string.demo_local_network_retry))
            } else message(getString(R.string.demo_local_network_denied))
        }

    /** 在生命周期协程中识别端点是否属于局域网，DNS 解析在 IO；API 37 起对识别出的局域网操作申请权限。 */
    protected fun withNetworkEndpointPermission(endpoint: String, action: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 37) { action(); return }
        lifecycleScope.launch {
            val host = runCatching {
                java.net.URI(if ("://" in endpoint) endpoint else "tcp://$endpoint").host
            }.getOrNull()?.trim('[', ']') ?: endpoint.trim()
            val local = withContext(Dispatchers.IO) {
                host.endsWith(".local", ignoreCase = true) ||
                    (!host.contains('.') && !host.contains(':') && host != "localhost") ||
                    runCatching {
                        java.net.InetAddress.getAllByName(host).any { address ->
                            !address.isLoopbackAddress &&
                                (address.isSiteLocalAddress || address.isLinkLocalAddress ||
                                    address.isMulticastAddress ||
                                    (address.address.size == 16 &&
                                        (address.address[0].toInt() and 0xfe) == 0xfc))
                        }
                    }.getOrDefault(false)
            }
            if (local) withLocalNetworkPermission(action) else action()
        }
    }

    /** 权限已满足时立即执行，否则先展示用途说明并暂存一次操作，待授权回调执行；拒绝时仅提示。 */
    protected fun withLocalNetworkPermission(action: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 37 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, "android.permission.ACCESS_LOCAL_NETWORK"
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else if (pendingLocalNetworkAction == null) {
            confirm(getString(R.string.demo_local_network_reason)) {
                pendingLocalNetworkAction = action
                localNetworkPermission.launch("android.permission.ACCESS_LOCAL_NETWORK")
            }
        }
    }

    private var pendingSaveId: String? = null
    private var displayedInput: String? = null
    private var displayedOutput: String? = null

    /** 在基类初始化前恢复待另存资产 ID，支持系统文件创建流程跨页面重建。 */
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        pendingSaveId = savedInstanceState?.getString("workspace-save")
        super.onCreate(savedInstanceState)
    }

    /** 保存待另存资产 ID，交由系统恢复文件创建流程的后续回调。 */
    override fun onSaveInstanceState(outState: android.os.Bundle) {
        outState.putString("workspace-save", pendingSaveId)
        super.onSaveInstanceState(outState)
    }

    private lateinit var documents: DocumentPickerHelper
    private lateinit var photos: PhotoPickerHelper
    private val saveOutput =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val id = pendingSaveId
            pendingSaveId = null
            if (uri != null && id != null)
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val file =
                                WorkspaceStore.get(applicationContext).find(id)?.file
                                    ?: error("Missing asset")
                            contentResolver.openOutputStream(uri)?.use { out ->
                                file.inputStream().use { it.copyTo(out) }
                            } ?: error("No stream")
                        }
                        message(getString(R.string.workspace_saved))
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        message(getString(R.string.demo_error_permission))
                    }
                }
        }
    private val activeButtons = mutableListOf<android.view.View>()

    /** 连接工具标题、素材入口和结果区，在 STARTED 生命周期收集状态并更新按钮、进度与预览。 */
    protected fun bindDemo(
        result: DemoOutputBinding,
        title: String,
        vararg buttons: android.view.View,
    ) {
        setPageTitle(title)
        val route = WorkspacePolicy.routeForActivity(javaClass.simpleName)
        viewModel.configureWorkspace(title, route)
        viewModel.receiveAssets(
            applicationContext,
            intent.getStringArrayListExtra(WorkspacePolicy.ASSET_IDS).orEmpty(),
        )
        activeButtons += buttons
        documents =
            DocumentPickerHelper.with(this)
                .register(onOpenMultiple = { viewModel.importFiles(applicationContext, it) })
        photos =
            PhotoPickerHelper.with(this).register(maxItems = 20) {
                viewModel.importFiles(applicationContext, it)
            }
        result.cancel.setOnClickListener { viewModel.cancel() }
        result.share.setOnClickListener { output(false) }
        result.open.setOnClickListener { output(true) }
        result.save.setOnClickListener {
            chooseResultFile { file ->
                lifecycleScope.launch {
                    val asset =
                        withContext(Dispatchers.IO) {
                            WorkspaceStore.get(applicationContext)
                                .register(file, "$title.${file.extension}", "output", title)
                        }
                    if (asset != null) {
                        pendingSaveId = asset.id
                        saveOutput.launch(asset.name)
                    }
                }
            }
        }
        result.continueWith.setOnClickListener {
            val files = resultFiles()
            lifecycleScope.launch {
                val assets =
                    withContext(Dispatchers.IO) {
                        files.mapNotNull { file ->
                            WorkspaceStore.get(applicationContext).findByFile(file)
                        }
                    }
                if (assets.isNotEmpty()) WorkspaceActions.chooseTool(this@DemoActivity, assets)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    activeButtons.forEach { it.isEnabled = !state.busy }
                    result.progress.isVisible = state.busy
                    result.progress.isIndeterminate = state.progress == 0
                    result.progress.progress = state.progress
                    result.cancel.isVisible = state.busy
                    result.inputs.text =
                        getString(R.string.demo_input_summary, state.inputs.size) +
                            state.inputs
                                .mapIndexed { i, file ->
                                    "\n${i + 1}. ${state.names[file.absolutePath] ?: file.name} (${android.text.format.Formatter.formatShortFileSize(this@DemoActivity, file.length())})"
                                }
                                .joinToString("")
                    result.message.text =
                        state.result?.text
                            ?: getString(
                                if (state.busy) R.string.demo_running else R.string.demo_ready
                            )
                    val hasFiles =
                        state.result?.let { it.file != null || it.files.isNotEmpty() } == true
                    result.save.isEnabled = !state.busy && hasFiles
                    result.continueWith.isEnabled = !state.busy && hasFiles
                    result.inputPreview.isVisible =
                        state.inputs.firstOrNull()?.extension?.let {
                            WorkspacePolicy.isImage(it)
                        } == true
                    state.inputs
                        .firstOrNull()
                        ?.takeIf { result.inputPreview.isVisible }
                        ?.let {
                            if (displayedInput != it.absolutePath) {
                                displayedInput = it.absolutePath
                                ImageLoaderUtil.load(result.inputPreview, it)
                            }
                        }
                    result.share.isEnabled = !state.busy && hasFiles
                    result.open.isEnabled = !state.busy && hasFiles
                    result.preview.isVisible = state.result?.preview != null
                    state.result?.preview?.let {
                        if (displayedOutput != it.absolutePath) {
                            displayedOutput = it.absolutePath
                            ImageLoaderUtil.load(result.preview, it)
                        }
                    }
                }
            }
        }
    }

    /** 让用户选择系统图片选择器或工作区现有图片作为输入。 */
    protected fun pickImages() {
        chooseSource(true) { photos.launchImages() }
    }

    /** 让用户选择系统文档选择器或工作区文件，系统入口按给定 MIME 类型过滤。 */
    protected fun pickFiles(vararg mime: String) {
        chooseSource(false) { documents.openMultiple(*mime) }
    }

    /** 展示素材来源选择；工作区查询在 IO，随后在页面仍存活的协程中展示文件列表。 */
    private fun chooseSource(images: Boolean, system: () -> Unit) {
        XPopup.Builder(this)
            .asBottomList(
                getString(R.string.workspace_input_source),
                arrayOf(
                    getString(R.string.workspace_system_files),
                    getString(R.string.workspace_existing_files),
                ),
            ) { index, _ ->
                if (index == 0) system()
                else
                    lifecycleScope.launch {
                        val assets =
                            withContext(Dispatchers.IO) {
                                WorkspaceStore.get(applicationContext).snapshot().assets.filter {
                                    !images || WorkspacePolicy.isImage(it.file.extension)
                                }
                            }
                        if (assets.isEmpty()) message(getString(R.string.workspace_no_assets))
                        else
                            XPopup.Builder(this@DemoActivity)
                                .asBottomList(
                                    getString(R.string.workspace_existing_files),
                                    assets.map { it.name }.toTypedArray(),
                                ) { selected, _ ->
                                    viewModel.selectWorkspaceAssets(
                                        applicationContext,
                                        listOf(assets[selected].id),
                                    )
                                }
                                .show()
                    }
            }
            .show()
    }

    /** 通过基础 DialogKit 展示调用方提供的资源化提示。 */
    protected fun message(text: String) {
        DialogKit.message(this, getString(R.string.demo_notice), text)
    }

    /** 通过基础确认弹窗展示操作说明，仅在用户确认后执行回调。 */
    protected fun confirm(text: String, action: () -> Unit) {
        DialogKit.confirm(
            this,
            getString(R.string.demo_notice),
            text,
            onConfirm = {
                action()
                true
            },
        )
    }

    /** 消费基础层导航事件并调用统一 Router；其他事件留给基类处理。 */
    override fun onBaseUiEvent(event: BaseUiEvent): Boolean =
        if (event is BaseUiEvent.Navigate) {
            Router.navigate(this, event.path)
            true
        } else false

    /** 合并主产物和批量产物并按绝对路径去重，尚无结果时返回空列表。 */
    private fun resultFiles(): List<File> =
        viewModel.state.value.result
            ?.let { (listOfNotNull(it.file) + it.files).distinctBy { file -> file.absolutePath } }
            .orEmpty()

    /** 单个产物直接回调，多个产物弹出选择列表，无产物时不执行操作。 */
    private fun chooseResultFile(action: (File) -> Unit) {
        val files = resultFiles()
        if (files.size == 1) action(files.first())
        else if (files.isNotEmpty())
            XPopup.Builder(this)
                .asBottomList(
                    getString(R.string.demo_output),
                    files.map { it.name }.toTypedArray(),
                ) { index, _ ->
                    action(files[index])
                }
                .show()
    }

    /** 先选择要操作的产物，再按参数交给打开或分享流程。 */
    private fun output(open: Boolean) {
        chooseResultFile { file -> outputFile(file, open) }
    }

    /** 通过 FileProvider 授予临时只读权限并打开或分享产物，无可用应用时展示提示。 */
    private fun outputFile(file: File, open: Boolean) {
        val mime =
            when (file.extension.lowercase()) {
                "pdf" -> "application/pdf"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "jpg",
                "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "zip" -> "application/zip"
                else -> "*/*"
            }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        try {
            if (open)
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            else if (!ShareUtil.shareFile(this, uri, mime, getString(R.string.demo_share)))
                message(getString(R.string.demo_no_reader))
        } catch (_: android.content.ActivityNotFoundException) {
            message(getString(R.string.demo_no_reader))
        }
    }

    /** 通用基类不主动加载业务数据，具体工具在自身初始化流程绑定输入与操作。 */
    override fun initData() = Unit
}
