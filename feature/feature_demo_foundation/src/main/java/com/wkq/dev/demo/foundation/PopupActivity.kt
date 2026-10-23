package com.wkq.dev.demo.foundation
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.foundation.databinding.DemoPopupBinding
import com.wkq.router.annotation.Route

@Route(path = "/foundation/lib_xpopup")
class PopupActivity : DemoActivity<DemoPopupBinding, PopupViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_popup_title), contentBinding.common, contentBinding.confirm, contentBinding.bottom)
        contentBinding.common.setOnClickListener { message(contentBinding.message.text.toString()) }
contentBinding.confirm.setOnClickListener { confirm(contentBinding.message.text.toString()) { viewModel.report(getString(R.string.demo_popup_success)) } }
contentBinding.bottom.setOnClickListener { com.lxj.xpopup.XPopup.Builder(this).asBottomList(contentBinding.title.text.toString(), arrayOf(getString(R.string.demo_popup_common),getString(R.string.demo_popup_confirm))) { index, text -> viewModel.report(text.orEmpty()) }.show() }

    }

}
class PopupViewModel : DemoViewModel() {

}
