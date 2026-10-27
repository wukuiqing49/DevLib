package com.wkq.dev.demo.files

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.files.databinding.DemoUsbBinding
import com.wkq.router.annotation.Route
import com.wkq.usb.UsbStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** SAF 存储独立业务页：授权目录、浏览、读取、上传与隔离验证，不宣称提供 USB 驱动。 */
@Route(path = "/files/core_usb")
class UsbActivity : DemoActivity<DemoUsbBinding, UsbViewModel>() {
    private val chooseTree = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == RESULT_OK && data != null && uri != null) {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
                message(getString(R.string.storage_no_tree)); return@registerForActivityResult
            }
            val persisted = if (data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                runCatching { contentResolver.takePersistableUriPermission(uri, flags); true }.getOrDefault(false)
            } else false
            viewModel.tree = uri.toString()
            contentBinding.path.setText("")
            contentBinding.tree.text = getString(if (persisted) R.string.usb_grant_persisted else R.string.usb_grant_temporary)
            viewModel.browse(applicationContext, "")
        }
    }

    /** 仅恢复树 URI，不假设历史授权仍有效；后续查询由系统检查实际权限与挂载状态。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (viewModel.tree.isEmpty()) viewModel.tree = savedInstanceState?.getString("usb-tree").orEmpty()
        if (viewModel.tree.isNotEmpty()) contentBinding.tree.text = getString(R.string.usb_grant_recheck)
    }

    /** 保存目录身份以支持系统选择器返回和进程恢复，不复制或扩展授权 flags。 */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("usb-tree", viewModel.tree)
        super.onSaveInstanceState(outState)
    }

    /** 复用 Base、文档选择与确认弹窗，目录行点击进入子目录或读取文件。 */
    override fun initView() {
        val b = contentBinding
        bindDemo(b.result, getString(R.string.usb_title), b.authorize, b.browse, b.up, b.probe, b.choose, b.upload)
        b.authorize.setOnClickListener {
            chooseTree.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            ))
        }
        b.browse.setOnClickListener { viewModel.browse(applicationContext, b.path.text.toString()) }
        b.up.setOnClickListener {
            val path = b.path.text.toString().trimEnd('/').substringBeforeLast('/', "")
            b.path.setText(path); viewModel.browse(applicationContext, path)
        }
        b.choose.setOnClickListener { pickFiles("*/*") }
        b.upload.setOnClickListener {
            confirm(getString(R.string.storage_upload_confirm)) {
                viewModel.upload(applicationContext, b.path.text.toString(), b.filename.text.toString())
            }
        }
        b.probe.setOnClickListener {
            confirm(getString(R.string.storage_probe_confirm)) { viewModel.verify(applicationContext, b.path.text.toString()) }
        }
        bindStorageRows(this, b.entries, this, viewModel.rows) { row ->
            if (!viewModel.state.value.busy) {
                if (row.directory) { b.path.setText(row.path); viewModel.browse(applicationContext, row.path) }
                else viewModel.download(applicationContext, row.path)
            }
        }
    }
}

/** 管理当前 SAF 目录与浏览状态，所有存储操作在公共执行器的 IO 线程运行。 */
class UsbViewModel : DemoViewModel() {
    var tree: String = ""
    private val mutableRows = MutableStateFlow<List<StorageRow>>(emptyList())
    val rows = mutableRows.asStateFlow()
    private var listedTree = ""
    private var listedDirectory = ""

    /** 检查已选择目录，实际权限由 ContentResolver 在每次操作时验证。 */
    private fun storage(context: Context): UsbStorage {
        if (tree.isEmpty()) throw DemoInputException(context.getString(R.string.storage_no_tree))
        return UsbStorage(context.applicationContext, tree)
    }

    /** 逐项列举当前子目录，最多显示两百项，保留未知大小与原始文件名称。 */
    fun browse(context: Context, directory: String) {
        mutableRows.value = emptyList()
        listedTree = ""
        execute(context) {
            val root = tree
            val job = currentCoroutineContext()
            val rows = mutableListOf<StorageRow>()
            var total = 0
            storage(context).visit(directory) {
                job.ensureActive(); total++
                if (rows.size < 200) rows += StorageRow(it.path, it.directory, it.size)
            }
            listedTree = root
            listedDirectory = directory
            mutableRows.value = rows
            DemoResult(context.getString(R.string.storage_listed, rows.size, total))
        }
    }

    /** 下载当前列表内的小文件到工作区，超限或拔盘失败时清理本次本地产物。 */
    fun download(context: Context, path: String) = execute(context) {
        require(tree == listedTree && rows.value.any { it.path == path && !it.directory })
        val job = currentCoroutineContext()
        val extension = path.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
        val output = DemoFiles.output(context, extension)
        downloadStorageFile(storage(context).read(path), output) { job.ensureActive() }
        DemoResult(context.getString(R.string.storage_downloaded), output)
    }

    /** 向已浏览目录独占新建文件，使用首个且唯一的本地输入，已有同名目标不覆盖。 */
    fun upload(context: Context, directory: String, name: String) = execute(context) {
        require(tree == listedTree && listedTree.isNotEmpty() && directory == listedDirectory)
        require(name.isNotBlank() && !name.contains('/') && name !in setOf(".", ".."))
        val path = listOf(directory, name).filter(String::isNotEmpty).joinToString("/")
        val source = selected().single()
        val job = currentCoroutineContext()
        source.inputStream().use { storage(context).write(path, it) { bytes ->
            job.ensureActive(); progress(if (source.length() > 0) (bytes * 100 / source.length()).toInt() else 0)
        } }
        DemoResult(context.getString(R.string.storage_uploaded, path))
    }

    /** 在已授权目录独占写入测试数据并读回清理；不删除已有文件或授权根。 */
    fun verify(context: Context, directory: String) = execute(context) {
        require(tree == listedTree && listedTree.isNotEmpty() && directory == listedDirectory)
        val name = listOf(directory, "devlib-probe-${java.util.UUID.randomUUID()}.bin").filter(String::isNotEmpty).joinToString("/")
        val job = currentCoroutineContext()
        val storage = storage(context)
        try {
            verifyStorageRoundTrip(name,
                write = { path, data -> data.inputStream().use { storage.write(path, it) { job.ensureActive() } } },
                read = storage::read, delete = storage::delete, check = { job.ensureActive() })
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { throw DemoInputException(context.getString(R.string.storage_probe_failed, name)) }
        DemoResult(context.getString(R.string.storage_probe_passed))
    }
}
