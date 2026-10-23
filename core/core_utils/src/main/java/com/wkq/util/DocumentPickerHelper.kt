package com.wkq.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

/**
 * Storage Access Framework 文件与目录选择工具。
 *
 * 返回的 Uri 可通过 [takePersistableReadWritePermission] 持久保留读写权限，适合后续跨进程或重启后继续访问。
 */
class DocumentPickerHelper private constructor(
    private val activity: ComponentActivity? = null,
    private val fragment: Fragment? = null
) {
    private val context: Context
        get() = activity ?: fragment?.requireContext() ?: error("Context is not available.")

    private var openLauncher: ActivityResultLauncher<Array<String>>? = null
    private var openMultipleLauncher: ActivityResultLauncher<Array<String>>? = null
    private var createLauncher: ActivityResultLauncher<String>? = null
    private var treeLauncher: ActivityResultLauncher<Uri?>? = null
    private var onOpen: ((Uri?) -> Unit)? = null
    private var onOpenMultiple: ((List<Uri>) -> Unit)? = null
    private var onCreate: ((Uri?) -> Unit)? = null
    private var onOpenTree: ((Uri?) -> Unit)? = null

    companion object {
        /** 为 ComponentActivity 创建文件选择工具。 */
        fun with(activity: ComponentActivity) = DocumentPickerHelper(activity = activity)

        /** 为 Fragment 创建文件选择工具。 */
        fun with(fragment: Fragment) = DocumentPickerHelper(fragment = fragment)
    }

    /**
     * 注册文件选择合约。必须在 Activity/Fragment 的 onCreate 阶段调用。
     *
     * @param onOpen 单文件选择结果，取消时为 null。
     * @param onOpenMultiple 多文件选择结果，取消时为空列表。
     * @param onCreate 新建文件结果，取消时为 null。
     * @param onOpenTree 目录选择结果，取消时为 null。
     */
    fun register(
        onOpen: (Uri?) -> Unit = {},
        onOpenMultiple: (List<Uri>) -> Unit = {},
        onCreate: (Uri?) -> Unit = {},
        onOpenTree: (Uri?) -> Unit = {}
    ): DocumentPickerHelper {
        this.onOpen = onOpen
        this.onOpenMultiple = onOpenMultiple
        this.onCreate = onCreate
        this.onOpenTree = onOpenTree

        this.openLauncher = registerForResult(ActivityResultContracts.OpenDocument()) { uri -> onOpen(uri) }
        this.openMultipleLauncher = registerForResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            onOpenMultiple(uris)
        }
        this.createLauncher = registerForResult(ActivityResultContracts.CreateDocument("*/*")) { uri -> onCreate(uri) }
        this.treeLauncher = registerForResult(ActivityResultContracts.OpenDocumentTree()) { uri -> onOpenTree(uri) }
        return this
    }

    /** 打开单文件选择器；未传 MIME 类型时允许所有文件。 */
    fun open(vararg mimeTypes: String) {
        requireLauncher(openLauncher, "open").launch(normalizeMimeTypes(mimeTypes))
    }

    /** 打开多文件选择器；未传 MIME 类型时允许所有文件。 */
    fun openMultiple(vararg mimeTypes: String) {
        requireLauncher(openMultipleLauncher, "openMultiple").launch(normalizeMimeTypes(mimeTypes))
    }

    /** 打开系统新建文件页面，文件类型由系统或用户选择的目标应用决定。 */
    fun create(fileName: String) {
        require(fileName.isNotBlank()) { "fileName must not be blank." }
        requireLauncher(createLauncher, "create").launch(fileName)
    }

    /** 打开目录选择器；[initialUri] 仅在系统支持时作为初始位置。 */
    fun openTree(initialUri: Uri? = null) {
        requireLauncher(treeLauncher, "openTree").launch(initialUri)
    }

    /** 尝试持久化 Uri 的读写权限。仅 SAF 返回且带有授权标记的 Uri 会成功。 */
    fun takePersistableReadWritePermission(uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            true
        }.getOrDefault(false)
    }

    /** 释放此前持久化的 Uri 读写权限。 */
    fun releasePersistableReadWritePermission(uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            true
        }.getOrDefault(false)
    }

    private fun normalizeMimeTypes(mimeTypes: Array<out String>): Array<String> {
        return mimeTypes.filter { it.isNotBlank() }.ifEmpty { listOf("*/*") }.toTypedArray()
    }

    private fun <I, O> registerForResult(
        contract: ActivityResultContract<I, O>,
        callback: (O) -> Unit
    ): ActivityResultLauncher<I>? {
        return when {
            activity != null -> activity.registerForActivityResult(contract, callback)
            fragment != null -> fragment.registerForActivityResult(contract, callback)
            else -> null
        }
    }

    private fun <I> requireLauncher(launcher: ActivityResultLauncher<I>?, operation: String): ActivityResultLauncher<I> {
        return checkNotNull(launcher) {
            "DocumentPickerHelper is not registered. Call register() before $operation()."
        }
    }
}
