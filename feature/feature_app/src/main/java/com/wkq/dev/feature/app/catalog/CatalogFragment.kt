package com.wkq.dev.feature.app.catalog

import android.content.Intent
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.lxj.xpopup.XPopup
import com.wkq.base.adapter.BaseRecyclerViewAdapter
import com.wkq.base.dialog.DialogKit
import com.wkq.base.fragment.BaseVMFragment
import com.wkq.dev.demo.common.*
import com.wkq.dev.feature.app.R
import com.wkq.dev.feature.app.databinding.FragmentCatalogBinding
import com.wkq.dev.feature.app.databinding.ItemDemoEntryBinding
import com.wkq.router.api.Router
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** 工作台列表展示项，组合标题、摘要与可选的工具、资产或操作记录，驱动对应按钮行为。 */
private data class WorkbenchRow(
    val title: String,
    val meta: String,
    val description: String,
    val entry: DemoEntry? = null,
    val asset: WorkspaceAsset? = null,
    val task: WorkspaceTask? = null,
)

/** 基于 BaseVMFragment 的工作台页面，展示工具、文件和操作历史，并编排导入、另存与跨工具流转。 */
class CatalogFragment : BaseVMFragment<FragmentCatalogBinding, CatalogViewModel>() {
    private var pendingSave: String? = null
    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            viewModel.import(requireContext().applicationContext, uris) {
                context?.let {
                    DialogKit.message(
                        it,
                        getString(R.string.workbench_notice),
                        getString(R.string.workbench_import_failed),
                    )
                }
            }
        }
    private val save =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val id = pendingSave
            pendingSave = null
            if (uri != null && id != null)
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val app = requireContext().applicationContext
                        withContext(Dispatchers.IO) {
                            val asset = WorkspaceStore.get(app).find(id) ?: error("Missing asset")
                            app.contentResolver.openOutputStream(uri)?.use { out ->
                                asset.file.inputStream().use { it.copyTo(out) }
                            } ?: error("Missing stream")
                        }
                        notice(getString(com.wkq.dev.demo.common.R.string.workspace_saved))
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        notice(getString(R.string.workbench_import_failed))
                    }
                }
        }
    private lateinit var adapter: BaseRecyclerViewAdapter<ItemDemoEntryBinding, WorkbenchRow>

    /** 绑定基础列表适配器、搜索和导航，在视图 STARTED 期间订阅工作区状态；底部 Insets 交由宿主统一处理。 */
    override fun initView() {
        // BaseTitleActivity 是 bottom inset owner，避免 Material 导航重复添加系统栏高度。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.navigation) { _, insets
            ->
            insets
        }

        pendingSave = savedInstanceStateId
        adapter =
            object :
                BaseRecyclerViewAdapter<ItemDemoEntryBinding, WorkbenchRow>(
                    requireContext(),
                    ItemDemoEntryBinding::inflate,
                ) {
                /** 根据条目类型绑定工具入口、资产选择或任务产物操作，重置复用行的按钮状态。 */
                override fun convert(
                    binding: ItemDemoEntryBinding,
                    item: WorkbenchRow,
                    position: Int,
                ) {
                    binding.title.text = item.title
                    binding.meta.text = item.meta
                    binding.description.text = item.description
                    binding.primary.isVisible =
                        item.entry != null ||
                            item.asset != null ||
                            item.task?.outputIds?.isNotEmpty() == true
                    binding.secondary.isVisible =
                        item.entry != null ||
                            item.asset != null ||
                            (item.task?.route?.isNotBlank() == true &&
                                item.task.status != "running")
                    binding.select.isVisible = item.asset != null
                    binding.root.setOnClickListener(null)
                    when {
                        item.entry != null -> {
                            binding.primary.setText(R.string.workbench_open)
                            binding.primary.setOnClickListener { open(item.entry) }
                            val developer = viewModel.filter.value.tab == "developer"
                            binding.secondary.setText(
                                if (developer) R.string.workbench_details
                                else if (item.entry.route in viewModel.snapshot.value.favorites)
                                    R.string.workbench_unpin
                                else R.string.workbench_pin
                            )
                            binding.secondary.setOnClickListener {
                                if (developer) details(item.entry)
                                else
                                    viewModel.favorite(
                                        requireContext().applicationContext,
                                        item.entry.route,
                                    )
                            }
                        }
                        item.asset != null -> {
                            binding.select.setText(
                                if (item.asset.id in viewModel.selection.value)
                                    R.string.workbench_deselect
                                else R.string.workbench_select
                            )
                            binding.select.setOnClickListener { viewModel.select(item.asset.id) }
                            binding.primary.setText(R.string.workbench_next)
                            binding.primary.setOnClickListener {
                                WorkspaceActions.chooseTool(requireContext(), listOf(item.asset))
                            }
                            binding.secondary.setText(R.string.workbench_asset_actions)
                            binding.secondary.setOnClickListener { assetActions(item.asset) }
                        }
                        item.task != null -> {
                            binding.primary.setText(R.string.workbench_task_outputs)
                            binding.primary.setOnClickListener {
                                viewModel.tab("assets")
                                viewModel.category("output")
                                val outputs =
                                    viewModel.snapshot.value.assets.filter {
                                        it.id in item.task.outputIds
                                    }
                                if (outputs.isEmpty()) notice(getString(R.string.workbench_missing))
                                else
                                    XPopup.Builder(requireContext())
                                        .asBottomList(
                                            getString(R.string.workbench_task_outputs),
                                            outputs.map { it.name }.toTypedArray(),
                                        ) { index, _ ->
                                            assetActions(outputs[index])
                                        }
                                        .show()
                            }
                            binding.secondary.setText(R.string.workbench_task_restart)
                            binding.secondary.setOnClickListener {
                                Router.navigate(
                                    requireContext(),
                                    Router.build(item.task.route)
                                        .withStringArrayList(
                                            WorkspacePolicy.ASSET_IDS,
                                            ArrayList(item.task.inputIds),
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.batch.setOnClickListener {
            val assets =
                viewModel.snapshot.value.assets.filter { it.id in viewModel.selection.value }
            XPopup.Builder(requireContext())
                .asBottomList(
                    getString(R.string.workbench_assets),
                    arrayOf(
                        getString(R.string.workbench_next),
                        getString(R.string.workbench_clear_selection),
                    ),
                ) { index, _ ->
                    if (index == 0) WorkspaceActions.chooseTool(requireContext(), assets)
                    else viewModel.clearSelection()
                }
                .show()
        }
        binding.importFiles.setOnClickListener {
            if (viewModel.loadFailed.value) viewModel.attach(requireContext().applicationContext)
            else picker.launch(arrayOf("*/*"))
        }
        binding.search.doAfterTextChanged { viewModel.search(it.toString()) }
        binding.navigation.setOnItemSelectedListener { item ->
            val tab =
                when (item.itemId) {
                    R.id.nav_tools -> "tools"
                    R.id.nav_assets -> "assets"
                    R.id.nav_developer -> "developer"
                    else -> "home"
                }
            if (viewModel.filter.value.tab != tab) viewModel.tab(tab)
            true
        }
        viewModel.attach(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                        viewModel.filter,
                        viewModel.snapshot,
                        viewModel.importing,
                        viewModel.selection,
                        viewModel.loadFailed,
                    ) { filter, data, importing, _, _ ->
                        Triple(filter, data, importing)
                    }
                    .collect { (filter, data, importing) -> render(filter, data, importing) }
            }
        }
    }

    private var savedInstanceStateId: String? = null

    /** 恢复系统另存流程对应的资产 ID，留待视图初始化接续。 */
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        savedInstanceStateId = savedInstanceState?.getString("pending-save")
        super.onCreate(savedInstanceState)
    }

    /** 保存待另存资产 ID，避免页面重建后失去结果回调关联。 */
    override fun onSaveInstanceState(outState: android.os.Bundle) {
        outState.putString("pending-save", pendingSave)
        super.onSaveInstanceState(outState)
    }

    /** 按当前标签和筛选构造列表，更新选择数、空态、加载失败提示及导航选中状态。 */
    private fun render(filter: CatalogFilter, data: WorkspaceSnapshot, importing: Boolean) {
        val heading =
            when (filter.tab) {
                "tools" -> R.string.workbench_tools
                "assets" -> R.string.workbench_assets
                "developer" -> R.string.workbench_developer
                else -> R.string.workbench_home
            }
        binding.heading.setText(heading)
        binding.subtitle.setText(
            when (filter.tab) {
                "tools" -> R.string.workbench_tools_intro
                "assets" -> R.string.workbench_assets_intro
                "developer" -> R.string.workbench_developer_intro
                else -> R.string.workbench_home_intro
            }
        )
        binding.searchBox.isVisible = filter.tab != "home"
        binding.batch.isVisible = filter.tab == "assets" && viewModel.selection.value.isNotEmpty()
        binding.batch.text = getString(R.string.workbench_batch, viewModel.selection.value.size)
        binding.importFiles.isVisible = filter.tab == "assets" || viewModel.loadFailed.value
        binding.importFiles.isEnabled = !importing
        binding.importFiles.setText(
            if (viewModel.loadFailed.value) R.string.workbench_retry
            else if (importing) R.string.workbench_importing else R.string.workbench_import
        )
        binding.searchBox.hint =
            getString(
                if (filter.tab == "assets") R.string.workbench_search_assets
                else R.string.catalog_search
            )
        if (binding.search.text.toString() != filter.query) binding.search.setText(filter.query)
        val selected =
            when (filter.tab) {
                "tools" -> R.id.nav_tools
                "assets" -> R.id.nav_assets
                "developer" -> R.id.nav_developer
                else -> R.id.nav_home
            }
        if (binding.navigation.selectedItemId != selected)
            binding.navigation.selectedItemId = selected
        val categories =
            when (filter.tab) {
                "assets" ->
                    linkedMapOf(
                        "" to R.string.workbench_all_assets,
                        "input" to R.string.workbench_inputs,
                        "output" to R.string.workbench_outputs,
                        "history" to R.string.workbench_history,
                    )
                "tools" ->
                    linkedMapOf("" to R.string.catalog_all).apply {
                        putAll(
                            DemoCatalog.categories.filterKeys {
                                it != "foundation" && it != "google"
                            }
                        )
                    }
                "developer" ->
                    linkedMapOf(
                        "" to R.string.catalog_all,
                        "foundation" to R.string.catalog_foundation,
                        "functional" to R.string.catalog_functional,
                        "google" to R.string.catalog_google,
                    )
                else -> linkedMapOf()
            }
        binding.filterScroll.isVisible = categories.isNotEmpty()
        if (binding.categories.tag != categories.keys.toList()) {
            binding.categories.removeAllViews()
            categories.forEach { (key, label) ->
                binding.categories.addView(
                    Chip(requireContext()).apply {
                        id = View.generateViewId()
                        tag = key
                        setText(label)
                        isCheckable = true
                        setOnClickListener { viewModel.category(key) }
                    }
                )
            }
            binding.categories.tag = categories.keys.toList()
        }
        for (i in 0 until binding.categories.childCount) (binding.categories.getChildAt(i) as Chip)
            .apply { isChecked = tag == filter.category }
        val rows = buildList {
            /** 按指定分组标签将工具条目转换后加入本次渲染列表。 */
            fun addTools(entries: List<DemoEntry>, label: Int) {
                entries.forEach { add(toolRow(it, label)) }
            }
            when (filter.tab) {
                "home" -> {
                    addTools(
                        DemoCatalog.entries.filter { it.route in data.favorites },
                        R.string.workbench_favorites,
                    )
                    addTools(
                        data.recent
                            .mapNotNull { route -> DemoCatalog.entries.find { it.route == route } }
                            .take(3),
                        R.string.workbench_recent,
                    )
                    if (data.favorites.isEmpty() && data.recent.isEmpty())
                        addTools(
                            DemoCatalog.entries.filter {
                                it.module in
                                    setOf(
                                        "core_image_render",
                                        "core_pdf",
                                        "lib_transfer",
                                        "lib_drop",
                                    )
                            },
                            R.string.workbench_start,
                        )
                    data.tasks
                        .filter { it.status == "running" }
                        .take(3)
                        .forEach { add(taskRow(it)) }
                    data.assets
                        .filter { it.kind == "output" }
                        .take(3)
                        .forEach { add(assetRow(it, R.string.workbench_recent_outputs)) }
                }
                "assets" -> {
                    if (filter.category == "history") {
                        data.tasks
                            .filter { it.title.contains(filter.query, true) }
                            .forEach { add(taskRow(it)) }
                        if (isEmpty())
                            add(
                                WorkbenchRow(
                                    getString(R.string.workbench_empty_history),
                                    "",
                                    getString(R.string.workbench_empty_history_body),
                                )
                            )
                    } else {
                        data.assets
                            .filter {
                                (filter.category.isBlank() || it.kind == filter.category) &&
                                    (it.name.contains(filter.query, true) ||
                                        it.tool.contains(filter.query, true))
                            }
                            .forEach { add(assetRow(it)) }
                        if (isEmpty())
                            add(
                                WorkbenchRow(
                                    getString(R.string.workbench_empty_assets),
                                    "",
                                    getString(R.string.workbench_empty_assets_body),
                                )
                            )
                    }
                }
                else -> {
                    val tools =
                        DemoCatalog.entries.filter { entry ->
                            val matchesTab =
                                filter.tab == "developer" ||
                                    entry.category !in setOf("foundation", "google")
                            val matchesCategory =
                                when (filter.category) {
                                    "" -> true
                                    "functional" -> entry.category != "foundation"
                                    else -> entry.category == filter.category
                                }
                            matchesTab &&
                                matchesCategory &&
                                (filter.query.isBlank() ||
                                    entry.module.contains(filter.query, true) ||
                                    getString(entry.title).contains(filter.query, true) ||
                                    getString(entry.intro).contains(filter.query, true))
                        }
                    addTools(
                        tools,
                        if (filter.tab == "developer") R.string.workbench_developer
                        else R.string.workbench_tools,
                    )
                }
            }
        }
        adapter.setData(rows)
        binding.empty.isVisible = rows.isEmpty() || viewModel.loadFailed.value
        binding.empty.setText(
            if (viewModel.loadFailed.value) R.string.workbench_load_failed
            else R.string.catalog_empty
        )
        binding.count.text =
            if (filter.tab == "home") getString(R.string.workbench_summary)
            else getString(R.string.workbench_section, getString(heading), rows.size)
    }

    /** 将工具资源标题、介绍和分组标签转换为可操作的工作台行。 */
    private fun toolRow(entry: DemoEntry, label: Int) =
        WorkbenchRow(
            getString(entry.title),
            getString(label),
            getString(entry.intro),
            entry = entry,
        )

    /** 将资产显示名、大小、时间和来源组成展示行，保留资产引用供后续操作。 */
    private fun assetRow(asset: WorkspaceAsset, label: Int? = null) =
        WorkbenchRow(
            asset.name,
            getString(
                R.string.workbench_asset_meta,
                getString(
                    label
                        ?: if (asset.kind == "output") R.string.workbench_outputs
                        else R.string.workbench_inputs
                ),
                Formatter.formatShortFileSize(requireContext(), asset.bytes),
                DateFormat.format("MM-dd HH:mm", asset.created),
            ),
            getString(
                R.string.workbench_asset_details,
                asset.file.extension.uppercase(),
                asset.tool.ifBlank { getString(R.string.workbench_device_source) },
            ),
            asset = asset,
        )

    /** 把前台操作状态映射为资源文案，组合产物数量、创建时间与手动重试说明。 */
    private fun taskRow(task: WorkspaceTask): WorkbenchRow {
        val status =
            when (task.status) {
                "completed" -> R.string.workbench_completed
                "cancelled" -> R.string.workbench_cancelled
                "interrupted" -> R.string.workbench_interrupted
                "running" -> R.string.workbench_active
                else -> R.string.workbench_failed
            }
        return WorkbenchRow(
            task.title,
            getString(R.string.workbench_task_meta, getString(status), task.outputs),
            DateFormat.format("MM-dd HH:mm", task.created).toString() +
                "\n" +
                getString(R.string.workbench_task_help),
            task = task,
        )
    }

    /** 记录工具访问历史并使用统一 Router 打开对应页面。 */
    private fun open(entry: DemoEntry) {
        viewModel.visit(requireContext().applicationContext, entry.route)
        Router.navigate(requireContext(), entry.route)
    }

    /** 展示模块类型、接入条件和源码依赖方式，区分 KSP 处理器与构建期插件。 */
    private fun details(entry: DemoEntry) {
        val setup =
            when (entry.module) {
                "core_router_processor" -> "ksp(project(\":core:${entry.module}\"))"
                "core_router_gradle_plugin" -> getString(R.string.workbench_plugin_setup)
                else -> "implementation(project(\":core:${entry.module}\"))"
            }
        notice(
            getString(
                R.string.workbench_library_detail,
                getString(entry.title),
                entry.module,
                getString(
                    if (entry.category == "foundation") R.string.catalog_foundation
                    else R.string.catalog_functional
                ),
                getString(entry.availability),
                entry.module.replace('_', '-'),
                getString(
                    if (entry.category == "foundation") R.string.workbench_foundation_boundary
                    else R.string.workbench_function_boundary
                ),
                setup,
            )
        )
    }

    /** 展示打开、分享、另存和改显示名操作；文件缺失时提示，数据库改名在 IO 执行。 */
    private fun assetActions(asset: WorkspaceAsset) {
        XPopup.Builder(requireContext())
            .asBottomList(
                asset.name,
                arrayOf(
                    getString(R.string.workbench_asset_open),
                    getString(R.string.workbench_asset_share),
                    getString(R.string.workbench_asset_save),
                    getString(R.string.workbench_asset_rename),
                ),
            ) { index, _ ->
                if (!asset.file.isFile) {
                    notice(getString(R.string.workbench_missing))
                    return@asBottomList
                }
                if (index == 3) {
                    XPopup.Builder(requireContext())
                        .asInputConfirm(
                            getString(R.string.workbench_asset_rename),
                            null,
                            asset.name,
                            getString(R.string.workbench_asset_rename),
                            com.lxj.xpopup.interfaces.OnInputConfirmListener { name ->
                                val clean = name.orEmpty().trim()
                                if (clean.isNotBlank())
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                WorkspaceStore.get(
                                                        requireContext().applicationContext
                                                    )
                                                    .rename(asset.id, clean)
                                            }
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            notice(getString(R.string.workbench_load_failed))
                                        }
                                    }
                            },
                        )
                        .show()
                } else if (index == 2) {
                    pendingSave = asset.id
                    save.launch(asset.name)
                } else {
                    val context = requireContext()
                    val uri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            asset.file,
                        )
                    val mime =
                        android.webkit.MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(asset.file.extension.lowercase())
                            ?: "application/octet-stream"
                    try {
                        val intent =
                            if (index == 0) Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            else
                                Intent(Intent.ACTION_SEND)
                                    .setType(mime)
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(
                            if (index == 0) intent else Intent.createChooser(intent, asset.name)
                        )
                    } catch (_: android.content.ActivityNotFoundException) {
                        notice(getString(com.wkq.dev.demo.common.R.string.demo_no_reader))
                    }
                }
            }
            .show()
    }

    /** 仅在 Fragment 仍附着时通过基础弹窗显示工作区提示。 */
    private fun notice(text: String) {
        if (isAdded) DialogKit.message(requireContext(), getString(R.string.workbench_notice), text)
    }

    /** 数据订阅已在视图初始化时建立，此处不重复启动加载。 */
    override fun initData() = Unit

    /** 解除列表与适配器的引用后交给基类释放视图。 */
    override fun onDestroyView() {
        binding.list.adapter = null
        super.onDestroyView()
    }
}
