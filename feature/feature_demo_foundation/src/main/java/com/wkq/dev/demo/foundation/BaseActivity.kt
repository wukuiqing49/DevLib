package com.wkq.dev.demo.foundation

import androidx.lifecycle.viewModelScope
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.foundation.databinding.DemoBaseBinding
import com.wkq.router.annotation.Route
import kotlinx.coroutines.*

/** 基础框架的宿主演示页，用真实基类接口展示加载、空态、错误态和确认事件。 */
@Route(path = "/foundation/core_base")
class BaseActivity : DemoActivity<DemoBaseBinding, BaseViewModel>() {
    /** 绑定页面状态切换和确认弹窗按钮，所有状态变化交由 ViewModel 发起。 */
    override fun initView() {
        bindDemo(
            contentBinding.result,
            getString(R.string.demo_base_title),
            contentBinding.loading,
            contentBinding.empty,
            contentBinding.error,
            contentBinding.restore,
            contentBinding.dialog,
        )
        contentBinding.loading.setOnClickListener { viewModel.loading() }
        contentBinding.empty.setOnClickListener {
            viewModel.empty(getString(R.string.demo_base_intro))
        }
        contentBinding.error.setOnClickListener {
            viewModel.failure(getString(R.string.demo_base_intro))
        }
        contentBinding.restore.setOnClickListener {
            viewModel.report(getString(R.string.demo_base_success))
        }
        contentBinding.dialog.setOnClickListener {
            viewModel.confirmEvent(
                getString(R.string.demo_base_title),
                getString(R.string.demo_base_intro),
            )
        }
    }
}

/** 基础状态演示的宿主 ViewModel，复用公共状态接口而不修改基础库实现。 */
class BaseViewModel : DemoViewModel() {
    /** 在 ViewModel 协程中显示加载态，延迟 1.2 秒后恢复内容，生命周期清理会取消协程。 */
    fun loading() {
        viewModelScope.launch {
            showLoading()
            delay(1200)
            showContent()
        }
    }

    /** 先记录提示内容，再切换到基础框架的空态。 */
    fun empty(text: String) {
        report(text)
        showEmpty(text)
    }

    /** 先记录提示内容，再切换到基础框架的错误态。 */
    fun failure(text: String) {
        report(text)
        showError(text)
    }

    /** 发送带固定请求标识的基础确认弹窗事件，由宿主页面消费。 */
    fun confirmEvent(title: String, text: String) {
        sendConfirmDialog("base-demo", title, text)
    }
}
