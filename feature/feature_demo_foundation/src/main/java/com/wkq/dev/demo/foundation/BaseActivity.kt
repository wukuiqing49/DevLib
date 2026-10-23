package com.wkq.dev.demo.foundation
import android.content.Context
import kotlinx.coroutines.flow.collect
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.foundation.databinding.DemoBaseBinding
import com.wkq.router.annotation.Route
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
@Route(path = "/foundation/core_base")
class BaseActivity : DemoActivity<DemoBaseBinding, BaseViewModel>() {
    override fun initView() {
        bindDemo(contentBinding.result, getString(R.string.demo_base_title), contentBinding.loading, contentBinding.empty, contentBinding.error, contentBinding.restore, contentBinding.dialog)
        contentBinding.loading.setOnClickListener { viewModel.loading() }
contentBinding.empty.setOnClickListener { viewModel.empty(getString(R.string.demo_base_intro)) }
contentBinding.error.setOnClickListener { viewModel.failure(getString(R.string.demo_base_intro)) }
contentBinding.restore.setOnClickListener { viewModel.report(getString(R.string.demo_base_success)) }
contentBinding.dialog.setOnClickListener { viewModel.confirmEvent(getString(R.string.demo_base_title),getString(R.string.demo_base_intro)) }

    }

}
class BaseViewModel : DemoViewModel() {
    fun loading(){ viewModelScope.launch { showLoading(); delay(1200);showContent() } }
fun empty(text:String){ report(text);showEmpty(text) }
fun failure(text:String){ report(text);showError(text) }
fun confirmEvent(title:String,text:String){sendConfirmDialog("base-demo",title,text)}
}
