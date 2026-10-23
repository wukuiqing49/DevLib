package com.wkq.dev.demo.foundation
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.foundation.databinding.DemoUtilsBinding
import com.wkq.router.annotation.Route
import com.wkq.util.FileUtil
import com.wkq.util.ShareUtil
@Route(path = "/foundation/core_utils")
class UtilsActivity : DemoActivity<DemoUtilsBinding, UtilsViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_utils_title), contentBinding.choose, contentBinding.sample, contentBinding.digest)
        contentBinding.choose.setOnClickListener { pickFiles("*/*") }
contentBinding.sample.setOnClickListener { viewModel.sample(applicationContext) }
contentBinding.digest.setOnClickListener { viewModel.digest(applicationContext) }
        contentBinding.share.setOnClickListener {
            val files = viewModel.state.value.inputs
            if (files.isEmpty()) message(getString(R.string.demo_utils_intro))
            else if (!ShareUtil.shareMultipleFiles(this, files)) message(getString(com.wkq.dev.demo.common.R.string.demo_no_reader))
        }
    }

}
class UtilsViewModel : DemoViewModel() {
    fun digest(context:Context)=execute(context){ val file=input(); val digest=requireNotNull(FileUtil.sha256(file));DemoResult("${file.name}\n${file.length()} B\nSHA-256\n"+digest,file) }
}
