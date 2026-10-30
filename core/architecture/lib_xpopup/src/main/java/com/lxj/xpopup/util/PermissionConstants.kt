package com.lxj.xpopup.util

import android.Manifest
import android.annotation.SuppressLint
import androidx.annotation.StringDef


/**
 * <pre>
 * author: Blankj
 * blog  : http://blankj.com
 * time  : 2017/12/29
 * desc  : constants of permission
</pre> *
 */
@SuppressLint("InlinedApi")
object PermissionConstants {
    const val STORAGE: String = "STORAGE"

    private val GROUP_STORAGE = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    fun getPermissions(@PermissionGroup permission: String?): Array<String> {
        if (permission == null) return emptyArray()
        if (permission == STORAGE) {
            return GROUP_STORAGE
        }

        return arrayOf(permission)
    }

    @StringDef(STORAGE)
    @Retention(AnnotationRetention.SOURCE)
    annotation class PermissionGroup
}
