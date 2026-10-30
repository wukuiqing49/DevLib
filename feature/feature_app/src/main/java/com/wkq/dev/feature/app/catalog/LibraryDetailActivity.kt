package com.wkq.dev.feature.app.catalog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.wkq.base.BaseViewModel
import com.wkq.base.activity.BaseVMTitleActivity
import com.wkq.dev.demo.common.WorkspaceStore
import com.wkq.dev.feature.app.R
import com.wkq.dev.feature.app.databinding.ActivityLibraryDetailBinding
import com.wkq.router.api.Router
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope

/** 独立库接入页，区分源码、发布依赖和宿主配置；路由体系按模块切换而非重复首页入口。 */
class LibraryDetailActivity : BaseVMTitleActivity<ActivityLibraryDetailBinding, LibraryDetailViewModel>() {
    /** 复用 Base 标题、返回与 Insets；文档采用 XML 自然换行，支持字体与窗口缩放。 */
    override fun initView() {
        setPageTitle(getString(R.string.integration_title))
        setLeftIcon(com.wkq.dev.demo.common.R.drawable.demo_back)
        setLeftClickListener { finish() }
        binding.titleBar.findViewById<android.view.View>(com.wkq.base.R.id.ll_left_container).apply {
            contentDescription = getString(com.wkq.dev.demo.common.R.string.demo_back)
            minimumWidth = resources.getDimensionPixelSize(com.wkq.dev.demo.common.R.dimen.demo_touch_target)
        }
        viewModel.initialize(intent.getStringExtra("module"))
        val b = contentBinding
        b.copy.setOnClickListener {
            val text = b.source.text.toString() + "\n" + b.published.text.toString()
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(getString(R.string.integration_dependency), text))
            android.widget.Toast.makeText(this, R.string.integration_copied, android.widget.Toast.LENGTH_SHORT).show()
        }
        b.experience.setOnClickListener {
            DirectoryPolicy.find(viewModel.module.value)?.let { entry -> viewModel.visit(applicationContext, entry.route); Router.navigate(this, entry.route) }
        }
        b.favorite.setOnClickListener { viewModel.favorite(applicationContext) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.module, viewModel.pinned) { module, pinned -> module to pinned }.collect { (module, pinned) ->
                    val entry = DirectoryPolicy.find(module)
                    if (entry == null) { finish(); return@collect }
                    b.title.setText(entry.title); b.module.text = module; b.requirement.setText(entry.availability)
                    b.location.text = getString(R.string.integration_location, getString(entry.section.title), entry.sourcePath)
                    b.overview.setText(entry.intro)
                    b.setup.setText(when {
                        module == "core_router_processor" -> R.string.integration_router_processor
                        module == "core_router_annotation" -> R.string.integration_router_annotation
                        module == "core_router_gradle_plugin" -> R.string.workbench_plugin_setup
                        module == "core_router_api" -> R.string.integration_router_note
                        module == "core_google" -> R.string.integration_google_note
                        module == "core_document" -> R.string.integration_document_note
                        module == "core_network" -> R.string.integration_network_note
                        entry.category == "files" -> R.string.integration_files_note
                        else -> R.string.integration_local_note
                    })
                    b.source.text = dependency(module, false)
                    b.published.text = dependency(module, true)
                    val router = module in DirectoryPolicy.routerModules
                    b.routerHeading.isVisible = router; b.routerModules.isVisible = router
                    if (router && b.routerModules.childCount == 0) DirectoryPolicy.routerModules.forEach { key ->
                        b.routerModules.addView(Chip(this@LibraryDetailActivity).apply {
                            id = android.view.View.generateViewId(); tag = key; text = key; isCheckable = true
                            setOnClickListener { viewModel.select(key, applicationContext) }
                        })
                    }
                    for (i in 0 until b.routerModules.childCount) (b.routerModules.getChildAt(i) as Chip).apply { isChecked = tag == module }
                    b.favorite.setText(if (pinned) R.string.directory_unfavorite else R.string.directory_favorite)
                    b.favorite.isVisible = module !in DirectoryPolicy.routerModules.drop(1)
                }
            }
        }
        viewModel.refresh(applicationContext)
    }

    /** 正确区分运行时、KSP 与构建插件依赖，不展示错误 implementation 指引。 */
    private fun dependency(module: String, published: Boolean): String {
        if (module == "core_router_gradle_plugin") return getString(R.string.workbench_plugin_setup)
        val notation = if (published) "\"com.wkq.dev:${module.replace('_', '-')}:<version>\"" else "project(\":core:$module\")"
        return "${if (module == "core_router_processor") "ksp" else "implementation"}($notation)"
    }
    /** 接入页无额外业务加载，状态由 ViewModel 管理。 */
    override fun initData() = Unit
}

/** 保存当前路由子模块与常用状态，数据库访问只在 IO，错误反馈沿用 Base 事件。 */
class LibraryDetailViewModel(private val saved: SavedStateHandle) : BaseViewModel() {
    private val selected = MutableStateFlow(saved.get<String>("detail-module").orEmpty())
    val module = selected.asStateFlow()
    private val favorite = MutableStateFlow(false)
    val pinned = favorite.asStateFlow()
    /** 首次打开从 Intent 读取模块，系统重建优先恢复当前子模块。 */
    fun initialize(module: String?) { if (selected.value.isBlank()) { selected.value = module.orEmpty(); saved["detail-module"] = selected.value } }
    /** 切换路由子模块并更新对应常用状态。 */
    fun select(module: String, context: Context) { if (DirectoryPolicy.find(module) != null) { selected.value = module; saved["detail-module"] = module; refresh(context) } }
    /** 查询所选模块是否常用，异步结果仅允许写回同一个模块。 */
    fun refresh(context: Context) {
        val target = selected.value
        viewModelScope.launch {
            try {
                val value = withContext(Dispatchers.IO) { DirectoryPolicy.find(target)?.route in WorkspaceStore.get(context.applicationContext).snapshot().favorites }
                if (selected.value == target) favorite.value = value
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { sendToast(context.getString(R.string.workbench_load_failed)) }
        }
    }
    /** 在 IO 切换常用并读取最新状态，不持有 Activity。 */
    fun favorite(context: Context) {
        val target = selected.value; val route = DirectoryPolicy.find(target)?.route ?: return
        viewModelScope.launch {
            try {
                val value = withContext(Dispatchers.IO) { val store = WorkspaceStore.get(context.applicationContext); store.favorite(route); route in store.snapshot().favorites }
                if (selected.value == target) favorite.value = value
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { sendToast(context.getString(R.string.workbench_load_failed)) }
        }
    }
    /** 访问记录写入 IO，失败仅提示不阻止能力页面打开。 */
    fun visit(context: Context, route: String) {
        viewModelScope.launch { try { withContext(Dispatchers.IO) { WorkspaceStore.get(context.applicationContext).visit(route) } }
            catch(cancel: CancellationException) { throw cancel } catch(_: Exception) { sendToast(context.getString(R.string.workbench_load_failed)) } }
    }
}
