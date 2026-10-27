package com.wkq.dev.demo.files

import android.content.Context
import android.widget.ArrayAdapter
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.files.databinding.DemoNasBinding
import com.wkq.naslib.*
import com.wkq.router.annotation.Route
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** NAS 独立业务页：协议配置、目录浏览、小文件读取、独占上传及隔离读写验证。 */
@Route(path = "/files/core_nas")
class NasActivity : DemoActivity<DemoNasBinding, NasViewModel>() {
    /** 绑定 Base 结果区与目录列表；权限和写入确认留在宿主，密码不保存到界面状态。 */
    override fun initView() {
        val b = contentBinding
        bindDemo(b.result, getString(R.string.nas_title), b.connect, b.up, b.probe, b.choose, b.upload, b.discover)
        b.protocol.adapter = ArrayAdapter.createFromResource(this, R.array.nas_protocols, android.R.layout.simple_spinner_dropdown_item)
        b.connect.setOnClickListener { browse(b.path.text.toString()) }
        b.up.setOnClickListener { browse(b.path.text.toString().trimEnd('/').substringBeforeLast('/', "")) }
        b.discover.setOnClickListener { withLocalNetworkPermission { viewModel.discover(applicationContext) } }
        b.choose.setOnClickListener { pickFiles("*/*") }
        b.upload.setOnClickListener {
            confirm(getString(R.string.storage_upload_confirm)) {
                connected { connection -> viewModel.upload(applicationContext, connection, b.path.text.toString(), b.filename.text.toString()) }
            }
        }
        b.probe.setOnClickListener {
            confirm(getString(R.string.storage_probe_confirm)) {
                connected { connection -> viewModel.verify(applicationContext, connection, b.path.text.toString()) }
            }
        }
        bindStorageRows(this, b.entries, this, viewModel.rows) { row ->
            if (!viewModel.state.value.busy) {
                if (row.directory) browse(row.path)
                else connected { connection -> viewModel.download(applicationContext, connection, row.path) }
            }
        }
    }

    /** 将用户输入转换成当前一次会话参数，在权限检查前拒绝无效 URI；不持久化凭据。 */
    private fun connected(action: (NasConnection) -> Unit) {
        val b = contentBinding
        val connection = try {
            NasConnection(NasProtocol.entries[b.protocol.selectedItemPosition], b.endpoint.text.toString().trim(),
                b.username.text.toString(), b.password.text.toString(), b.fingerprint.text.toString().trim())
        } catch (_: Exception) {
            message(getString(R.string.nas_invalid_connection)); return
        }
        withNetworkEndpointPermission(connection.root) { action(connection) }
    }

    /** 保留用户输入的相对路径，浏览前清除旧列表，避免连接变更后误操作旧条目。 */
    private fun browse(path: String) {
        contentBinding.path.setText(path)
        connected { connection -> viewModel.browse(applicationContext, connection, path) }
    }
}

/** 在 IO 创建并关闭独立 NAS 会话，目录状态与操作结果跨配置变化保留，不持有 Activity。 */
class NasViewModel : DemoViewModel() {
    private val mutableRows = MutableStateFlow<List<StorageRow>>(emptyList())
    val rows = mutableRows.asStateFlow()
    private var listedConnection: List<String>? = null
    private var listedPath = ""

    /** 目录选择绑定到同一会话配置，凭据仅留在内存，不进入操作历史或 saved state。 */
    private fun identity(connection: NasConnection) = listOf(connection.protocol.name, connection.root,
        connection.username, connection.password, connection.hostKeySha256)

    /** 逐项读取目录，最多展示两百行并明确截断；配置或路径改变后必须重新浏览。 */
    fun browse(context: Context, connection: NasConnection, path: String) {
        mutableRows.value = emptyList()
        listedConnection = null
        execute(context) {
            val job = currentCoroutineContext()
            NasClient.open(connection).use { storage ->
                val rows = mutableListOf<StorageRow>()
                var total = 0
                storage.visit(path) {
                    job.ensureActive()
                    total++
                    if (rows.size < 200) rows += StorageRow(it.path, it.directory, it.size)
                }
                listedConnection = identity(connection)
                listedPath = NasPaths.normalize(path)
                mutableRows.value = rows
                DemoResult(context.getString(R.string.storage_listed, rows.size, total))
            }
        }
    }

    /** 只从当前已浏览配置下载文件，最多 8 MiB；流、会话和失败本地产物均受控释放。 */
    fun download(context: Context, connection: NasConnection, path: String) = execute(context) {
        require(listedConnection == identity(connection) && rows.value.any { it.path == path && !it.directory })
        val job = currentCoroutineContext()
        val extension = NasPaths.name(path).substringAfterLast('.', "bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
        val output = DemoFiles.output(context, extension)
        NasClient.open(connection).use { storage ->
            downloadStorageFile(storage.read(path), output) { job.ensureActive() }
        }
        DemoResult(context.getString(R.string.storage_downloaded), output)
    }

    /** 对当前已浏览目录独占新建上传首个输入文件，不覆盖已有目标，不保存服务器密码。 */
    fun upload(context: Context, connection: NasConnection, directory: String, name: String) = execute(context) {
        require(listedConnection == identity(connection) && listedPath == NasPaths.normalize(directory))
        require(name.isNotBlank() && NasPaths.name(name) == name && !name.contains('/'))
        val path = NasPaths.join(directory, name)
        val source = selected().single()
        val job = currentCoroutineContext()
        NasClient.open(connection).use { storage ->
            source.inputStream().use { storage.write(path, it, source.length()) { bytes ->
                job.ensureActive(); progress(if (source.length() > 0) (bytes * 100 / source.length()).toInt() else 0)
            } }
        }
        DemoResult(context.getString(R.string.storage_uploaded, path))
    }

    /** 在当前目录写入唯一测试文件、读回比对并清理；失败保留路径提示，取消原样传播。 */
    fun verify(context: Context, connection: NasConnection, directory: String) = execute(context) {
        require(listedConnection == identity(connection) && listedPath == NasPaths.normalize(directory))
        val job = currentCoroutineContext()
        val name = NasPaths.join(directory, "devlib-probe-${java.util.UUID.randomUUID()}.bin")
        NasClient.open(connection).use { storage ->
            try {
                verifyStorageRoundTrip(name,
                    write = { path, data -> data.inputStream().use { storage.write(path, it, data.size.toLong()) { job.ensureActive() } } },
                    read = storage::read, delete = storage::delete, check = { job.ensureActive() })
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { throw DemoInputException(context.getString(R.string.storage_probe_failed, name)) }
        }
        DemoResult(context.getString(R.string.storage_probe_passed))
    }

    /** 使用库的可取消 DNS-SD 发现，仅报告广播服务，不自动连接或信任发现的主机。 */
    fun discover(context: Context) = execute(context) {
        val found = NasDiscovery(context).discover()
        DemoResult(context.getString(R.string.nas_discovered, found.size) + found.joinToString("") {
            "\n${it.protocol.name} · ${it.name} · ${it.host}:${it.port}"
        })
    }
}
