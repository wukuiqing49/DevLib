package com.wkq.dev.demo.foundation

import android.content.Context
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.foundation.databinding.DemoUtilsBinding
import com.wkq.router.annotation.Route
import com.wkq.util.FileUtil
import com.wkq.util.ShareUtil

/** 工具基础能力的宿主页面，提供文件选择、摘要计算和多文件分享。 */
@Route(path = "/foundation/core_utils")
class UtilsActivity : DemoActivity<DemoUtilsBinding, UtilsViewModel>() {
    /** 绑定文件来源、SHA-256 计算及批量分享，无素材或缺少接收应用时提示。 */
    override fun initView() {
        bindDemo(
            contentBinding.result,
            getString(R.string.demo_utils_title),
            contentBinding.choose,
            contentBinding.sample,
            contentBinding.digest,
        )
        contentBinding.choose.setOnClickListener { pickFiles("*/*") }
        contentBinding.sample.setOnClickListener { viewModel.sample(applicationContext) }
        contentBinding.digest.setOnClickListener { viewModel.digest(applicationContext) }
        contentBinding.share.setOnClickListener {
            val files = viewModel.state.value.inputs
            if (files.isEmpty()) message(getString(R.string.demo_utils_intro))
            else if (!ShareUtil.shareMultipleFiles(this, files))
                message(getString(com.wkq.dev.demo.common.R.string.demo_no_reader))
        }
    }
}

/** 在 IO 调用已有文件工具计算摘要，将文件与摘要作为统一结果发布。 */
class UtilsViewModel : DemoViewModel() {
    /** 读取首个素材并计算 SHA-256，返回名称、字节数和摘要；计算失败交由公共错误处理。 */
    fun digest(context: Context) =
        execute(context) {
            val file = input()
            val digest = requireNotNull(FileUtil.sha256(file))
            DemoResult("${file.name}\n${file.length()} B\nSHA-256\n" + digest, file)
        }
}
