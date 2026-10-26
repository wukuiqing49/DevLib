package com.wkq.dev.demo.foundation

import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.foundation.databinding.DemoPopupBinding
import com.wkq.router.annotation.Route

/** 弹窗基础能力的宿主页面，提供消息、确认及底部列表的交互体验。 */
@Route(path = "/foundation/lib_xpopup")
class PopupActivity : DemoActivity<DemoPopupBinding, PopupViewModel>() {
    /** 绑定自定义弹窗文案和三类弹窗入口，将用户确认或选择结果交给 ViewModel。 */
    override fun initView() {
        bindDemo(
            contentBinding.result,
            getString(R.string.demo_popup_title),
            contentBinding.common,
            contentBinding.confirm,
            contentBinding.bottom,
        )
        contentBinding.common.setOnClickListener { message(contentBinding.message.text.toString()) }
        contentBinding.confirm.setOnClickListener {
            confirm(contentBinding.message.text.toString()) {
                viewModel.report(getString(R.string.demo_popup_success))
            }
        }
        contentBinding.bottom.setOnClickListener {
            com.lxj.xpopup.XPopup.Builder(this)
                .asBottomList(
                    contentBinding.title.text.toString(),
                    arrayOf(
                        getString(R.string.demo_popup_common),
                        getString(R.string.demo_popup_confirm),
                    ),
                ) { index, text ->
                    viewModel.report(text.orEmpty())
                }
                .show()
        }
    }
}

/** 复用通用结果状态记录弹窗交互，无额外业务数据源。 */
class PopupViewModel : DemoViewModel() {}
