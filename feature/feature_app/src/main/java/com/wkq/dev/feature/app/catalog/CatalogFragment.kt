package com.wkq.dev.feature.app.catalog

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import com.wkq.base.BaseViewModel
import com.wkq.base.fragment.BaseVMFragment
import com.wkq.dev.feature.app.R
import com.wkq.dev.feature.app.databinding.FragmentCatalogBinding

/** 架构、功能、三方服务和工作区四个独立入口，保留各页视图、筛选和滚动位置。 */
class CatalogFragment : BaseVMFragment<FragmentCatalogBinding, NavigationViewModel>() {
    /** 恢复系统保存的子 Fragment，底部 Insets 由 BaseTitleActivity 唯一消费。 */
    override fun initView() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.navigation) { _, insets -> insets }
        binding.navigation.setOnItemSelectedListener { item ->
            navigate(when (item.itemId) { R.id.nav_foundation -> "foundation"; R.id.nav_thirdparty -> "thirdparty"; R.id.nav_assets -> "workspace"; else -> "libraries" })
            true
        }
        val tab = viewModel.selected
        binding.navigation.selectedItemId = when (tab) { "foundation" -> R.id.nav_foundation; "thirdparty" -> R.id.nav_thirdparty; "workspace" -> R.id.nav_assets; else -> R.id.nav_tools }
        navigate(tab)
    }

    /** 切换可见页并限制隐藏页生命周期，避免重新创建列表和重置筛选。 */
    private fun navigate(tab: String) {
        if (childFragmentManager.isStateSaved) return
        viewModel.select(tab)
        val tag = viewModel.selected
        val existing = childFragmentManager.findFragmentByTag(tag)
        val target = existing ?: when (tag) {
            "workspace" -> WorkspaceFragment()
            else -> LibraryListFragment().apply { arguments = androidx.core.os.bundleOf("section" to tag) }
        }
        childFragmentManager.beginTransaction().apply {
            childFragmentManager.fragments.forEach { fragment ->
                if (fragment != target) { hide(fragment); setMaxLifecycle(fragment, Lifecycle.State.STARTED) }
            }
            if (existing == null) add(R.id.pages, target, tag) else show(target)
            setMaxLifecycle(target, Lifecycle.State.RESUMED)
            setPrimaryNavigationFragment(target)
        }.commitNow()
    }

    /** 页面数据由子页面各自加载，导航容器不读取业务数据。 */
    override fun initData() = Unit
}

/** 保存顶层导航选择，兼容系统重建时恢复选中页面。 */
class NavigationViewModel(private val saved: SavedStateHandle) : BaseViewModel() {
    val selected: String get() = validTab(saved["navigation"])
    /** 只保存合法导航键，首次或未知状态回到架构库。 */
    fun select(tab: String) { saved["navigation"] = validTab(tab) }
    /** 兼容旧导航键并排除内部支撑或损坏状态，工作区不参与库分类。 */
    private fun validTab(tab: String?): String = if (tab == "workspace") tab else (LibrarySection.fromKey(tab) ?: LibrarySection.ARCHITECTURE).key
}
