package com.wkq.logreport

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import java.io.File

/** 通过系统文档选择器保存报告，重建后保留待保存文件位置。 */
class ReportSaveFragment : Fragment() {
    private var pending: String? = null
    /** 从系统重建状态恢复待保存附件路径，不恢复过期Activity。 */
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pending = state?.getString("pending")
    }
    /** 选择用户可访问的保存目标。 */
    fun save(file: File) {
        pending = file.absolutePath
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, file.name), 710)
    }
    /** 文档选择器切换或旋转时保留待保存文件位置。 */
    override fun onSaveInstanceState(state: Bundle) { super.onSaveInstanceState(state); state.putString("pending", pending) }
    /** 用户选择目标后后台写入文件，提示使用Application上下文避免页面泄漏。 */
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != 710) return
        val context = context?.applicationContext ?: return
        val file = pending?.let(::File)
        pending = null
        if (result != Activity.RESULT_OK || file == null) return
        val target = data?.data ?: return
        Thread {
            val success = runCatching {
                context.contentResolver.openOutputStream(target, "w").use { output ->
                    checkNotNull(output)
                    file.inputStream().use { it.copyTo(output) }
                }
            }.isSuccess
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, if (success) R.string.log_report_saved else R.string.log_report_error, android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
