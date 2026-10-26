package com.wkq.dev.demo.files

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.wkq.base.adapter.BaseRecyclerViewAdapter
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.files.databinding.DemoTransferBinding
import com.wkq.dev.demo.files.databinding.ItemTransferTaskBinding
import com.wkq.nas.transfer.*
import com.wkq.router.annotation.Route
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** 多协议文件传输宿主页，提供端点配置、连接测试、任务选择与队列控制。 */
@Route(path = "/files/lib_transfer")
class TransferActivity : DemoActivity<DemoTransferBinding, TransferViewModel>() {
    private val protocols =
        listOf(
            StorageProtocol.LOCAL,
            StorageProtocol.SMB,
            StorageProtocol.WEBDAV,
            StorageProtocol.SFTP,
        )

    /** 绑定协议表单和基础任务适配器，按生命周期观察队列并接入权限与确认流程。 */
    override fun initView() {
        val b = contentBinding
        bindDemo(
            b.result,
            getString(R.string.demo_transfer_title),
            b.choose,
            b.sample,
            b.enqueue,
            b.refresh,
            b.pause,
            b.resume,
            b.canceljob,
            b.testConnection,
        )
        b.protocol.adapter =
            ArrayAdapter.createFromResource(
                this,
                R.array.demo_transfer_protocols,
                android.R.layout.simple_spinner_dropdown_item,
            )
        b.protocol.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                /** 未选中协议时保持表单现状，等待有效选择回调。 */
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit

                /** 根据是否为本地协议显示或隐藏远程连接表单。 */
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    b.remote.visibility =
                        if (protocols[position] == StorageProtocol.LOCAL) View.GONE
                        else View.VISIBLE
                }
            }
        b.choose.setOnClickListener { pickFiles("*/*") }
        b.sample.setOnClickListener { viewModel.sample(applicationContext) }
        b.testConnection.setOnClickListener {
            val target = endpoint()
            withEndpointPermission(target) { viewModel.testConnection(applicationContext, target) }
        }
        b.enqueue.setOnClickListener {
            val endpoint = endpoint()
            val name = b.name.text.toString().trim()
            confirm(getString(R.string.demo_transfer_confirm, name)) {
                withEndpointPermission(endpoint) { viewModel.enqueue(applicationContext, name, endpoint) }
            }
        }
        b.refresh.setOnClickListener { viewModel.refresh(applicationContext) }
        b.pause.setOnClickListener { viewModel.control(applicationContext, 0) }
        b.resume.setOnClickListener { resumeSelectedTask() }
        b.canceljob.setOnClickListener {
            confirm(getString(R.string.demo_transfer_cancel_confirm)) {
                viewModel.control(applicationContext, 2)
            }
        }
        val adapter =
            object :
                BaseRecyclerViewAdapter<ItemTransferTaskBinding, TaskSnapshot>(
                    this,
                    ItemTransferTaskBinding::inflate,
                ) {
                /** 展示任务目标、状态与字节进度，标记选中项并绑定任务选择。 */
                override fun convert(
                    binding: ItemTransferTaskBinding,
                    item: TaskSnapshot,
                    position: Int,
                ) {
                    binding.title.text =
                        getString(R.string.demo_transfer_row, item.targetPath, item.state.name)
                    binding.detail.text =
                        getString(
                            R.string.demo_transfer_row_detail,
                            item.id,
                            item.bytesTransferred,
                            item.totalBytes,
                        )
                    binding.root.isSelected = item.id == viewModel.screen.value.selectedId
                    binding.root.setOnClickListener {
                        viewModel.select(applicationContext, item.id)
                    }
                }
            }
        b.tasks.layoutManager = LinearLayoutManager(this)
        b.tasks.adapter = adapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.screen.collect { screen ->
                    adapter.setData(screen.tasks)
                    val selected = screen.tasks.firstOrNull { it.id == screen.selectedId }
                    b.taskStatus.text =
                        selected?.let {
                            getString(
                                R.string.demo_transfer_current,
                                it.id,
                                it.state.name,
                                it.bytesTransferred,
                                it.totalBytes,
                                it.error?.name.orEmpty(),
                            )
                        } ?: getString(R.string.demo_transfer_no_task)
                }
            }
        }
        viewModel.observe(applicationContext)
    }

    /** 在 IO 读取所选任务的真实目标配置，重新核对局域网权限后恢复；缺失配置时提示。 */
    private fun resumeSelectedTask() {
        lifecycleScope.launch {
            try {
                val id = viewModel.screen.value.selectedId
                val config = withContext(Dispatchers.IO) {
                    val task = id?.let { TransferClient.get(applicationContext).getTask(it) }
                    val targetId = task?.targetId
                    FileClient(applicationContext).observeConnections().first()
                        .firstOrNull { it.id == targetId }?.config
                }
                if (config == null) {
                    message(getString(R.string.demo_transfer_no_task))
                } else {
                    withEndpointPermission(
                        DemoTransferEndpoint(config.protocol, config.root, "", "", "")
                    ) { viewModel.control(applicationContext, 1) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message(getString(R.string.demo_transfer_no_task))
            }
        }
    }

    /** 本地目标直接执行，远程目标先交给通用端点权限判断。 */
    private fun withEndpointPermission(target: DemoTransferEndpoint, action: () -> Unit) {
        if (target.protocol == StorageProtocol.LOCAL) action()
        else withNetworkEndpointPermission(target.root, action)
    }

    /** 读取表单为本次端点草稿，保留密码原文而清理其他输入首尾空白。 */
    private fun endpoint() =
        DemoTransferEndpoint(
            protocols[contentBinding.protocol.selectedItemPosition.coerceAtLeast(0)],
            contentBinding.rootUrl.text.toString().trim(),
            contentBinding.user.text.toString().trim(),
            contentBinding.password.text.toString(),
            contentBinding.hostKey.text.toString().trim(),
        )
}

/** 宿主连接草稿，包含协议、根路径、账号、密码和主机指纹；不得直接记录到日志。 */
data class DemoTransferEndpoint(
    val protocol: StorageProtocol,
    val root: String,
    val username: String,
    val password: String,
    val hostKey: String,
)

/** 传输页展示状态，包含当前任务快照及待控制任务的 ID。 */
data class TransferScreen(
    val tasks: List<TaskSnapshot> = emptyList(),
    val selectedId: String? = null,
)

/** 编排连接测试和演示队列操作，恢复任务选择并登记本地传输产物。 */
class TransferViewModel : DemoViewModel() {
    private val mutableScreen = MutableStateFlow(TransferScreen())
    val screen = mutableScreen.asStateFlow()
    private var watching: Job? = null
    private var tested: DemoTransferEndpoint? = null

    /** 建立单个任务订阅，在 IO 登记完成的本地复制产物，并恢复演示任务选择。 */
    fun observe(context: Context) {
        if (watching?.isActive == true) return
        val app = context.applicationContext
        val saved = preferences(app).getString("selected_task", null)
        mutableScreen.value = mutableScreen.value.copy(selectedId = saved)
        watching =
            viewModelScope.launch {
                TransferClient.get(app).observeRecentTasks(1000).flowOn(Dispatchers.IO).collect {
                    all ->
                    withContext(Dispatchers.IO) {
                        val localId = preferences(app).getString("workspace_local_target", null)
                        all.filter { it.targetId == localId && it.state == TaskState.SUCCEEDED }
                            .forEach { task ->
                                val file =
                                    File(app.filesDir, "shared/demo/transfers/${task.targetPath}")
                                WorkspaceStore.get(app)
                                    .register(
                                        file,
                                        file.name,
                                        "output",
                                        app.getString(R.string.demo_transfer_title),
                                    )
                            }
                    }
                    val restored = restoreDemoTaskSelection(all, mutableScreen.value.selectedId)
                    mutableScreen.value = restored
                    preferences(app).edit().putString("selected_task", restored.selectedId).apply()
                }
            }
    }

    /** 仅允许选择当前列表中的任务，将选中 ID 保存到宿主偏好。 */
    fun select(context: Context, id: String) {
        if (screen.value.tasks.none { it.id == id }) return
        mutableScreen.value = screen.value.copy(selectedId = id)
        preferences(context).edit().putString("selected_task", id).apply()
    }

    /** 保存连接配置并以 20 秒超时读取少量目录项，成功后记住本次已测试端点。 */
    fun testConnection(context: Context, endpoint: DemoTransferEndpoint) =
        execute(context) {
            val id = saveTarget(context, endpoint)
            val page = withTimeout(20_000) { FileClient(context).listPage(id, "", limit = 5) }
            tested = endpoint
            DemoResult(context.getString(R.string.demo_transfer_connected, page.entries.size))
        }

    /** 校验目标文件名和远程端点测试状态，创建保留重名且校验内容的复制或上传任务，并选中返回的任务 ID。 */
    fun enqueue(context: Context, name: String, endpoint: DemoTransferEndpoint) =
        execute(context) {
            if (
                name.isBlank() ||
                    name == "." ||
                    name == ".." ||
                    name.any { it == '/' || it == '\\' || it.code < 32 }
            ) {
                throw DemoInputException(context.getString(R.string.demo_transfer_invalid_name))
            }
            if (endpoint.protocol != StorageProtocol.LOCAL && endpoint != tested) {
                throw DemoInputException(context.getString(R.string.demo_transfer_test_first))
            }
            val source = input()
            val targetId = saveTarget(context, endpoint)
            val client = TransferClient.get(context)
            val sourceId = "demo-source-${source.parentFile!!.name}"
            client.saveConnection(
                sourceId,
                ConnectionConfig(StorageProtocol.LOCAL, source.parent!!),
            )
            val id =
                client.enqueue(
                    TransferRequest(
                        sourceId,
                        source.name,
                        targetId,
                        name,
                        ConflictPolicy.KEEP_BOTH,
                        kind =
                            if (endpoint.protocol == StorageProtocol.LOCAL) TransferKind.COPY
                            else TransferKind.UPLOAD,
                        verifyContent = true,
                    )
                )
            preferences(context).edit().putString("selected_task", id).apply()
            withContext(Dispatchers.Main) {
                mutableScreen.value = screen.value.copy(selectedId = id)
            }
            DemoResult(context.getString(R.string.demo_transfer_enqueued, id))
        }

    /** 在 IO 查询当前选中任务并生成状态摘要，任务不存在时交给资源化错误处理。 */
    fun refresh(context: Context) =
        execute(context) {
            val task =
                TransferClient.get(context).getTask(currentId(context))
                    ?: throw DemoInputException(context.getString(R.string.demo_transfer_no_task))
            DemoResult(
                context.getString(
                    R.string.demo_transfer_current,
                    task.id,
                    task.state.name,
                    task.bytesTransferred,
                    task.totalBytes,
                    task.error?.name.orEmpty(),
                )
            )
        }

    /** 在 IO 控制所选任务：0 暂停、1 恢复、其他值取消；提示引擎是否接受变更。 */
    fun control(context: Context, action: Int) =
        execute(context) {
            val client = TransferClient.get(context)
            val id = currentId(context)
            val changed =
                when (action) {
                    0 -> client.pause(id)
                    1 -> client.resume(id)
                    else -> client.cancel(id)
                }
            DemoResult(
                context.getString(
                    if (changed) R.string.demo_transfer_controlled
                    else R.string.demo_transfer_unchanged
                )
            )
        }

    /** 获取当前所选任务 ID，无选择时抛出可显示的参数错误。 */
    private fun currentId(context: Context) =
        screen.value.selectedId
            ?: throw DemoInputException(context.getString(R.string.demo_transfer_no_task))

    /** 构造并保存目标配置，用非密码连接字段摘要生成 ID；本地目标固定为工作区传输目录。 */
    private suspend fun saveTarget(context: Context, endpoint: DemoTransferEndpoint): String {
        val local = File(context.filesDir, "shared/demo/transfers").apply { mkdirs() }
        val config =
            if (endpoint.protocol == StorageProtocol.LOCAL)
                ConnectionConfig(StorageProtocol.LOCAL, local.path)
            else
                ConnectionConfig(
                    endpoint.protocol,
                    endpoint.root,
                    endpoint.username,
                    endpoint.hostKey,
                )
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(
                    "${config.protocol}|${config.root}|${config.username}|${config.hostKeySha256}"
                        .toByteArray()
                )
                .joinToString("") { "%02x".format(it) }
        val id = "demo-target-$digest"
        if (endpoint.protocol == StorageProtocol.LOCAL)
            preferences(context).edit().putString("workspace_local_target", id).apply()
        TransferClient.get(context)
            .saveConnection(
                id,
                config,
                if (endpoint.protocol == StorageProtocol.LOCAL) "" else endpoint.password,
            )
        return id
    }

    /** 获取宿主传输偏好，用于保存任务选择与本地目标 ID。 */
    private fun preferences(context: Context) =
        context.getSharedPreferences("transfer_demo", Context.MODE_PRIVATE)
}

/** 只恢复演示创建的任务，避免控制消费宿主的其他任务。 */
internal fun restoreDemoTaskSelection(
    all: List<TaskSnapshot>,
    preferredId: String?,
): TransferScreen {
    val tasks =
        all.filter {
                it.sourceId.startsWith("demo-source-") &&
                    (it.targetId == "demo-target" || it.targetId.startsWith("demo-target-"))
            }
            .take(50)
    val selected =
        preferredId?.takeIf { id -> tasks.any { it.id == id } } ?: tasks.firstOrNull()?.id
    return TransferScreen(tasks, selected)
}
