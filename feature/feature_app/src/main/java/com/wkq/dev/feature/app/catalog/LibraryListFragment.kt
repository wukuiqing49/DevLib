package com.wkq.dev.feature.app.catalog

import android.content.Intent
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.wkq.base.adapter.BaseRecyclerViewAdapter
import com.wkq.base.fragment.BaseVMFragment
import com.wkq.dev.feature.app.R
import com.wkq.dev.feature.app.databinding.FragmentLibraryListBinding
import com.wkq.dev.feature.app.databinding.ItemDemoEntryBinding
import com.wkq.router.api.Router
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 三类库目录各自持有状态与滚动位置，复用现有 Base 列表，不与工作区文件列表混用。 */
class LibraryListFragment : BaseVMFragment<FragmentLibraryListBinding, CatalogViewModel>() {
    private val section: LibrarySection
        get() = LibrarySection.fromKey(arguments?.getString("section"))
            ?: if (arguments?.getBoolean("foundation") == true) LibrarySection.ARCHITECTURE else LibrarySection.FUNCTIONAL
    private lateinit var adapter: BaseRecyclerViewAdapter<ItemDemoEntryBinding, DemoEntry>

    /** 绑定紧凑库列表、分类和常用入口，沿用 Base 组件与宿主窗口适配。 */
    override fun initView() {
        if (resources.configuration.screenHeightDp < 480) binding.headerAppBar.setExpanded(false, false)
        binding.heading.setText(section.title)
        binding.subtitle.setText(section.intro)
        binding.search.setText(viewModel.filter.value.query)
        binding.search.doAfterTextChanged { viewModel.search(it.toString()) }
        adapter = object : BaseRecyclerViewAdapter<ItemDemoEntryBinding, DemoEntry>(requireContext(), ItemDemoEntryBinding::inflate) {
            /** 模块、运行条件和双入口始终可见；长说明仅目录截断，详情保留全文。 */
            override fun convert(binding: ItemDemoEntryBinding, item: DemoEntry, position: Int) {
                val router = item.module == "core_router_api"
                binding.title.setText(DirectoryPresentation.title(item))
                binding.module.text = if (router) "core_router_api · KSP · Gradle" else item.module
                binding.meta.text = getString(R.string.directory_meta, getString(DemoCatalog.categories.getValue(item.category)), getString(item.availability))
                binding.description.setText(DirectoryPresentation.summary(item))
                binding.primary.setOnClickListener { open(item) }
                binding.secondary.setOnClickListener { integration(item) }
            }
        }
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        val categories = linkedMapOf("" to R.string.catalog_all)
        val availableCategories = DirectoryPolicy.entries(section).map { it.category }.toSet()
        if (section == LibrarySection.FUNCTIONAL) categories.putAll(DemoCatalog.categories.filterKeys { it in availableCategories })
        if (viewModel.filter.value.category !in categories) viewModel.category("")
        binding.filterScroll.isVisible = section == LibrarySection.FUNCTIONAL
        categories.forEach { (key, label) -> binding.categories.addView(Chip(requireContext()).apply {
            id = View.generateViewId(); tag = key; setText(label); isCheckable = true
            setOnClickListener { viewModel.category(key) }
        }) }
        binding.retry.setOnClickListener { viewModel.attach(requireContext().applicationContext) }
        viewModel.attach(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(viewModel.filter, viewModel.snapshot, viewModel.loadFailed) { filter, snapshot, failed -> Triple(filter, snapshot, failed) }
                    .collect { (filter, snapshot, failed) ->
                        val entries = DirectoryPolicy.filter(section, filter.category, filter.query) { getString(DirectoryPresentation.title(it)) + " " + getString(it.title) + " " + getString(it.intro) }
                        if (adapter.getData() != entries) adapter.setData(entries)
                        for (i in 0 until binding.categories.childCount) (binding.categories.getChildAt(i) as Chip).apply { isChecked = tag == filter.category }
                        binding.count.text = if (filter.query.isBlank() && filter.category.isBlank()) {
                            val count = DirectoryPolicy.entries(section).size
                            if (section == LibrarySection.ARCHITECTURE) getString(R.string.directory_foundation_count, count, DemoCatalog.entries.count { it.section == section })
                            else resources.getQuantityString(R.plurals.directory_library_count, count, count)
                        } else getString(R.string.directory_result_count, entries.size)
                        binding.empty.isVisible = entries.isEmpty()
                        binding.empty.setText(R.string.catalog_empty)
                        binding.retry.isVisible = failed
                        val shortcuts = DirectoryPolicy.shortcuts(section, snapshot.favorites, snapshot.recent)
                        binding.shortcutsLabel.isVisible = false
                        binding.shortcutsScroll.isVisible = shortcuts.isNotEmpty() && filter.query.isBlank()
                        binding.shortcuts.removeAllViews()
                        shortcuts.forEach { entry -> binding.shortcuts.addView(Chip(requireContext()).apply {
                            setText(DirectoryPresentation.title(entry))
                            setOnClickListener { open(entry) }
                        }) }
                    }
            }
        }
    }
    /** 记录访问并进入原独立能力流程，不新建第二套演示页。 */
    private fun open(entry: DemoEntry) { viewModel.visit(requireContext().applicationContext, entry.route); Router.navigate(requireContext(), entry.route) }
    /** 接入信息使用独立详情页，保留系统返回栈。 */
    private fun integration(entry: DemoEntry) { startActivity(Intent(requireContext(), LibraryDetailActivity::class.java).putExtra("module", entry.module)) }
    /** 数据已随视图建立订阅，不重复加载。 */
    override fun initData() = Unit
    /** 视图销毁解除适配器，滚动状态交给 RecyclerView 的系统恢复机制。 */
    override fun onDestroyView() { binding.list.adapter = null; super.onDestroyView() }
}
