package com.wkq.photo.mediastore

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** 为仪器测试提供可撤销读取权限的 Content URI，不依赖系统相册的真实用户内容。 */
class RegressionContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (denyRead) throw SecurityException("Regression provider read access revoked")
        val target = File(requireNotNull(context).cacheDir, SOURCE_FILE)
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.wkq.photo.mediastore.regression"
        const val SOURCE_FILE = "regression-content.png"
        @Volatile var denyRead: Boolean = false
    }
}
