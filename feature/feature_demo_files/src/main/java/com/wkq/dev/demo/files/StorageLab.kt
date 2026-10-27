package com.wkq.dev.demo.files

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wkq.base.adapter.BaseRecyclerViewAdapter
import com.wkq.dev.demo.files.databinding.ItemStorageEntryBinding
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 宿主目录行模型，只保存路径、目录标记和可选大小，不混入账号与任务队列。 */
data class StorageRow(val path: String, val directory: Boolean, val size: Long)

/** 复用 Base Adapter 展示最多两百个目录项；选择目录或文件的行为由各业务页面决定。 */
internal fun bindStorageRows(
    context: Context, list: RecyclerView, owner: LifecycleOwner,
    rows: StateFlow<List<StorageRow>>, select: (StorageRow) -> Unit,
) {
    val adapter = object : BaseRecyclerViewAdapter<ItemStorageEntryBinding, StorageRow>(
        context, ItemStorageEntryBinding::inflate,
    ) {
        /** 展示原始文件身份与大小，点击回传完整相对路径，不修改 Unicode 名称。 */
        override fun convert(binding: ItemStorageEntryBinding, item: StorageRow, position: Int) {
            binding.name.text = item.path.substringAfterLast('/')
            binding.detail.text = context.getString(
                if (item.directory) R.string.storage_directory else R.string.storage_file,
                item.size,
            )
            binding.root.setOnClickListener { select(item) }
        }
    }
    list.layoutManager = LinearLayoutManager(context)
    list.adapter = adapter
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) { rows.collect { adapter.setData(it) } }
    }
}

/** 在确认的新文件上写入、读回并校验固定测试数据；只删除本次成功创建的文件。 */
internal fun verifyStorageRoundTrip(
    name: String = "devlib-probe-${UUID.randomUUID()}.bin",
    write: (String, ByteArray) -> Unit,
    read: (String) -> InputStream,
    delete: (String) -> Unit,
    check: () -> Unit,
): String {
    val expected = ByteArray(4096) { (it % 251).toByte() }
    // write 必须是独占新建语义；写入失败不删除未知归属的目标。
    write(name, expected)
    var failure: Throwable? = null
    try {
        check()
        val actual = read(name).use { it.readBytesBounded(expected.size, check) }
        require(actual.contentEquals(expected)) { "Read-back verification failed" }
        return name
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try { delete(name) } catch (cleanup: Exception) {
            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
        }
    }
}

/** 有界读取用于小文件验证，每块检查取消，超过上限时拒绝继续占用内存。 */
internal fun InputStream.readBytesBounded(limit: Int, check: () -> Unit): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        check()
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        require(output.size().toLong() + count <= limit) { "File exceeds preview limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** 将远端小文件读入新的工作区输出；失败或取消时仅清理本次创建的本地文件。 */
internal fun downloadStorageFile(input: InputStream, output: File, check: () -> Unit): File {
    try {
        input.use { source ->
            output.outputStream().use { target ->
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    check()
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    count += read
                    require(count <= 8L * 1024 * 1024) { "File exceeds lab download limit" }
                    target.write(buffer, 0, read)
                }
            }
        }
        return output
    } catch (error: Throwable) {
        output.delete()
        throw error
    }
}
