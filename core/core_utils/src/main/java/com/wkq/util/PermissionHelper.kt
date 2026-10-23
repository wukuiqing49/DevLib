package com.wkq.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * 基于 Activity Result API 的运行时权限请求结果。
 *
 * [permanentlyDenied] 表示系统不再建议展示权限说明，调用方可据此引导用户前往系统设置页。
 */
data class PermissionResult(
    /** 已授予的权限。 */
    val granted: Set<String>,
    /** 被拒绝的权限。 */
    val denied: Set<String>,
    /** 被拒绝且不应再展示权限说明的权限。 */
    val permanentlyDenied: Set<String>
) {
    /** 本次请求的权限是否全部已授予。 */
    val allGranted: Boolean get() = denied.isEmpty()
}

/**
 * 运行时权限请求工具。
 *
 * 请在 Activity/Fragment 的 onCreate 阶段调用 [register]，并由宿主在 Manifest 中声明所请求的危险权限。
 */
class PermissionHelper private constructor(
    private val activity: ComponentActivity? = null,
    private val fragment: Fragment? = null
) {
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var onResult: ((PermissionResult) -> Unit)? = null
    private var requestedPermissions: List<String> = emptyList()

    companion object {
        /** 为 ComponentActivity 创建权限工具。 */
        fun with(activity: ComponentActivity) = PermissionHelper(activity = activity)

        /** 为 Fragment 创建权限工具。 */
        fun with(fragment: Fragment) = PermissionHelper(fragment = fragment)

        /** Android 13+ 返回通知权限；低版本不需要运行时请求，返回空集合。 */
        fun notificationPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

        /** 根据媒体类型返回读取媒体库所需的权限集合。系统 Photo Picker 本身不需要调用该方法。 */
        fun mediaReadPermissions(type: PickMediaType): List<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                buildList {
                    if (type != PickMediaType.VIDEO_ONLY) add(Manifest.permission.READ_MEDIA_IMAGES)
                    if (type != PickMediaType.IMAGE_ONLY) add(Manifest.permission.READ_MEDIA_VIDEO)
                }
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        /** 判断单个权限当前是否已授予。 */
        fun hasPermission(context: Context, permission: String): Boolean {
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 注册权限请求回调。必须在 Activity/Fragment 的 onCreate 阶段调用。
     *
     * @param onResult 系统权限弹窗返回后的聚合结果。
     */
    fun register(onResult: (PermissionResult) -> Unit): PermissionHelper {
        this.onResult = onResult
        val callback: (Map<String, Boolean>) -> Unit = { result ->
            val granted = result.filterValues { it }.keys
            val denied = requestedPermissions.filterNot { it in granted }.toSet()
            val permanentlyDenied = denied.filterNot(::shouldShowRationale).toSet()
            onResult(PermissionResult(granted, denied, permanentlyDenied))
        }
        launcher = when {
            activity != null -> activity.registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
                callback
            )
            fragment != null -> fragment.registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
                callback
            )
            else -> null
        }
        return this
    }

    /** 发起权限请求；空权限列表会直接回调一个全部成功的结果。 */
    fun launch(vararg permissions: String) {
        val requested = permissions.filter { it.isNotBlank() }.distinct()
        if (requested.isEmpty()) {
            onResult?.invoke(PermissionResult(emptySet(), emptySet(), emptySet()))
            return
        }
        checkNotNull(launcher) { "PermissionHelper is not registered. Call register() during initialization first." }
        requestedPermissions = requested
        launcher?.launch(requested.toTypedArray())
    }

    private fun shouldShowRationale(permission: String): Boolean {
        return activity?.shouldShowRequestPermissionRationale(permission)
            ?: fragment?.shouldShowRequestPermissionRationale(permission)
            ?: false
    }
}
