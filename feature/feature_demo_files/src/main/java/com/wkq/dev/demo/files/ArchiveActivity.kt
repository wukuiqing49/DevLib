package com.wkq.dev.demo.files

import android.content.Context
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.files.databinding.DemoArchiveBinding
import com.wkq.photo.archive.*
import com.wkq.photo.core.operation.*
import com.wkq.router.annotation.Route
import kotlinx.coroutines.*

/** 压缩与解压宿主页面，连接文件选择、密码输入及归档产物展示。 */
@Route(path = "/files/core_archive")
class ArchiveActivity : DemoActivity<DemoArchiveBinding, ArchiveViewModel>() {
    /** 绑定输入选择、示例生成及压缩解压按钮，复用通用结果区。 */
    override fun initView() {
        bindDemo(
            contentBinding.result,
            getString(R.string.demo_archive_title),
            contentBinding.choose,
            contentBinding.sample,
            contentBinding.compress,
            contentBinding.extract,
        )
        contentBinding.choose.setOnClickListener { pickFiles("*/*") }
        contentBinding.sample.setOnClickListener { viewModel.sample(applicationContext) }
        contentBinding.compress.setOnClickListener {
            viewModel.compress(applicationContext, contentBinding.password.text.toString())
        }
        contentBinding.extract.setOnClickListener {
            viewModel.extract(applicationContext, contentBinding.password.text.toString())
        }
    }
}

/** 在 IO 调用归档库并向引擎传递协程取消，处理完成后统一登记工作区产物。 */
class ArchiveViewModel : DemoViewModel() {
    /** 将已选文件写成 ZIP，非空密码启用加密；结束时清空临时密码字符数组。 */
    fun compress(context: Context, password: String) =
        execute(context) {
            val out = DemoFiles.output(context, "zip")
            val request = ZipArchiveRequest(selected(), out)
            val job = currentCoroutineContext()
            if (password.isEmpty())
                ZipArchiveCreator.create(request, isCancelled = { !job.isActive })
            else {
                val chars = password.toCharArray()
                try {
                    ZipArchiveCreator.createEncrypted(
                        request,
                        chars,
                        isCancelled = { !job.isActive },
                    )
                } finally {
                    chars.fill('\u0000')
                }
            }
            DemoResult(context.getString(R.string.demo_archive_success), out)
        }

    /** 将首个归档解压到新工作目录，返回全部产物并在结束时清空临时密码字符数组。 */
    fun extract(context: Context, password: String) =
        execute(context) {
            val folder = DemoFiles.output(context, "dir")
            val chars = password.toCharArray()
            val job = currentCoroutineContext()
            try {
                val result =
                    EncryptedArchiveExtractor.extract(
                            EncryptedArchiveExtractRequest(input(), folder, chars),
                            cancellation = OperationCancellation { !job.isActive },
                        )
                        .getOrThrow()
                DemoResult(
                    result.files.joinToString("\n") { it.name + " (${it.length()} B)" },
                    files = result.files,
                )
            } finally {
                chars.fill('\u0000')
            }
        }
}
