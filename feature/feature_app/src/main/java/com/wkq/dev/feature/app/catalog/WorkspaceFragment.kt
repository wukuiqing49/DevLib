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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.lxj.xpopup.XPopup
import com.wkq.base.adapter.BaseRecyclerViewAdapter
import com.wkq.base.dialog.DialogKit
import com.wkq.base.fragment.BaseVMFragment
import com.wkq.dev.demo.common.*
import com.wkq.dev.feature.app.R
import com.wkq.dev.feature.app.databinding.FragmentWorkspaceBinding
import com.wkq.dev.feature.app.databinding.ItemWorkspaceFileBinding
import com.wkq.dev.feature.app.databinding.ItemWorkspaceHistoryBinding
import com.wkq.router.api.Router
import com.wkq.util.coil.ImageLoaderUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** 独立工作区页面，文件与操作记录使用各自适配器，不再混入功能库目录。 */
class WorkspaceFragment : BaseVMFragment<FragmentWorkspaceBinding, CatalogViewModel>() {
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
                        notice(getString(com.wkq.dev.demo.common.R.string.demo_error_io))
                    }
                }
        }
    private var savedInstanceStateId: String? = null

    /** 恢复系统另存流程对应的资产 ID，留待视图初始化接续。 */
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        savedInstanceStateId = savedInstanceState?.getString("pending-save")
        for (key in listOf("files", "history")) {
            @Suppress("DEPRECATION")
            val state = savedInstanceState?.getParcelable<android.os.Parcelable>("scroll-$key")
            if (state != null) scrollStates[key] = state
        }
        super.onCreate(savedInstanceState)
    }

    /** 保存待另存资产 ID，避免页面重建后失去结果回调关联。 */
    override fun onSaveInstanceState(outState: android.os.Bundle) {
        outState.putString("pending-save", pendingSave)
        if (_binding != null) lastSection?.let { scrollStates[it] = binding.list.layoutManager?.onSaveInstanceState() }
        scrollStates.forEach { (key, state) -> outState.putParcelable("scroll-$key", state) }
        super.onSaveInstanceState(outState)
    }


    private lateinit var files: BaseRecyclerViewAdapter<ItemWorkspaceFileBinding, WorkspaceAsset>
    private lateinit var history: BaseRecyclerViewAdapter<ItemWorkspaceHistoryBinding, WorkspaceTask>
    private var lastSection: String? = null
    private val scrollStates = mutableMapOf<String, android.os.Parcelable?>()

    /** 创建文件缩略图列表与操作记录列表，系统选择器和业务状态复用既有宿主能力。 */
    override fun initView() {
        if (resources.configuration.screenHeightDp < 480) binding.headerAppBar.setExpanded(false, false)
        binding.heading.setText(R.string.nav_workspace)
        binding.searchBox.setHint(R.string.workbench_search_assets)
        binding.search.setText(viewModel.filter.value.query)
        binding.search.doAfterTextChanged { viewModel.search(it.toString()) }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        files = object : BaseRecyclerViewAdapter<ItemWorkspaceFileBinding, WorkspaceAsset>(requireContext(), ItemWorkspaceFileBinding::inflate) {
            /** 展示文件名、类型和时间，图片采用现有加载器；绑定复用时清理旧勾选监听。 */
            override fun convert(binding: ItemWorkspaceFileBinding, item: WorkspaceAsset, position: Int) {
                binding.title.text = item.name
                binding.meta.text = getString(R.string.workspace_compact_meta,
                    getString(if (item.kind == "output") R.string.workbench_outputs else R.string.workbench_inputs),
                    Formatter.formatShortFileSize(requireContext(), item.bytes), DateFormat.getDateFormat(requireContext()).format(java.util.Date(item.created)))
                ImageLoaderUtil.load(binding.thumbnail, if (WorkspacePolicy.isImage(item.file.extension)) item.file else R.drawable.ic_workspace_assets,
                    placeholder = R.drawable.ic_workspace_assets, error = R.drawable.ic_workspace_assets)
                binding.select.setOnCheckedChangeListener(null)
                binding.select.isChecked = item.id in viewModel.selection.value
                binding.select.contentDescription = getString(R.string.workspace_asset_selected, item.name)
                binding.select.setOnCheckedChangeListener { button, _ ->
                    if (!viewModel.select(item.id)) { button.setOnCheckedChangeListener(null); button.isChecked = false; notice(getString(R.string.workspace_selection_limit)) }
                }
                binding.actions.setOnClickListener { assetActions(item) }
                binding.root.setOnClickListener { assetActions(item) }
            }
        }
        history = object : BaseRecyclerViewAdapter<ItemWorkspaceHistoryBinding, WorkspaceTask>(requireContext(), ItemWorkspaceHistoryBinding::inflate) {
            /** 操作状态与真实产物关联，重配前校验原输入仍在；不把历史当成自动重跑队列。 */
            override fun convert(binding: ItemWorkspaceHistoryBinding, item: WorkspaceTask, position: Int) {
                binding.title.text = item.title
                val status = when(item.status) { "completed" -> R.string.workbench_completed; "running" -> R.string.workbench_active; "cancelled" -> R.string.workbench_cancelled; "interrupted" -> R.string.workbench_interrupted; else -> R.string.workbench_failed }
                binding.meta.text = getString(R.string.workspace_compact_meta, getString(status),
                    getString(R.string.workbench_task_meta, DateFormat.getDateFormat(requireContext()).format(java.util.Date(item.created)), item.outputs),
                    DemoCatalog.entries.find { it.route == item.route }?.let { getString(it.title) }.orEmpty())
                binding.outputs.isVisible = item.outputIds.isNotEmpty()
                binding.outputs.setOnClickListener {
                    val outputs = viewModel.snapshot.value.assets.filter { it.id in item.outputIds }
                    if (outputs.isEmpty()) notice(getString(R.string.workbench_missing))
                    else XPopup.Builder(requireContext()).asBottomList(getString(R.string.workbench_task_outputs), outputs.map { it.name }.toTypedArray()) { index, _ -> assetActions(outputs[index]) }.show()
                }
                binding.restart.isVisible = item.status != "running" && item.route.isNotBlank()
                binding.restart.setOnClickListener {
                    val valid = viewModel.snapshot.value.assets.map { it.id }.toSet()
                    if (!valid.containsAll(item.inputIds)) notice(getString(R.string.workbench_missing))
                    else Router.navigate(requireContext(), Router.build(item.route).withStringArrayList(WorkspacePolicy.ASSET_IDS, ArrayList(item.inputIds)))
                }
            }
        }
        files.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        history.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.sections.addOnButtonCheckedListener { _, id, checked -> if (checked) viewModel.section(if (id == R.id.historyTab) "history" else "files") }
        linkedMapOf("" to R.string.workbench_all_assets, "input" to R.string.workbench_inputs, "output" to R.string.workbench_outputs).forEach { (key, title) ->
            binding.categories.addView(Chip(requireContext()).apply { id=View.generateViewId(); tag=key; setText(title); isCheckable=true; setOnClickListener { viewModel.category(key) } })
        }
        binding.importFiles.setOnClickListener {
            if (viewModel.loadFailed.value) viewModel.attach(requireContext().applicationContext) else picker.launch(arrayOf("*/*"))
        }
        binding.batch.setOnClickListener { nextTool(viewModel.snapshot.value.assets.filter { it.id in viewModel.selection.value }) }
        binding.clearSelection.setOnClickListener { viewModel.clearSelection() }
        pendingSave = savedInstanceStateId
        viewModel.attach(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(viewModel.filter, viewModel.snapshot, viewModel.selection, viewModel.importing, viewModel.loadFailed) { filter, data, selection, importing, failed ->
                    render(filter, data, selection, importing, failed)
                }.collect { }
            }
        }
    }

    /** 独立筛选文件和历史，只有切换内部页面时交换适配器，保留各自滚动位置。 */
    private fun render(filter: CatalogFilter, data: WorkspaceSnapshot, selected: Set<String>, importing: Boolean, failed: Boolean) {
        val showingHistory = filter.section == "history"
        if (binding.search.text.toString() != filter.query) binding.search.setText(filter.query)
        binding.sections.check(if (showingHistory) R.id.historyTab else R.id.filesTab)
        binding.subtitle.setText(if (showingHistory) R.string.workspace_history_intro else R.string.workspace_intro)
        binding.filterScroll.isVisible = !showingHistory
        for(i in 0 until binding.categories.childCount) (binding.categories.getChildAt(i) as Chip).apply { isChecked = tag == filter.category }
        binding.importFiles.isVisible = !showingHistory || failed
        binding.importFiles.isEnabled = !importing
        binding.importFiles.setText(if (failed) R.string.workbench_retry else if (importing) R.string.workbench_importing else R.string.workbench_import)
        binding.batch.isVisible = !showingHistory && selected.isNotEmpty()
        binding.clearSelection.isVisible = binding.batch.isVisible
        binding.batch.text = getString(R.string.workbench_batch, selected.size)
        val entries = data.assets.filter { (filter.category.isBlank() || filter.category == it.kind) && (filter.query.isBlank() || it.name.contains(filter.query,true) || it.tool.contains(filter.query,true)) }
        val tasks = data.tasks.filter { filter.query.isBlank() || it.title.contains(filter.query,true) }
        if(lastSection != filter.section) {
            lastSection?.let { scrollStates[it] = binding.list.layoutManager?.onSaveInstanceState() }
            binding.list.adapter = if(showingHistory) history else files
            lastSection = filter.section
            scrollStates[filter.section]?.let { binding.list.layoutManager?.onRestoreInstanceState(it) }
        }
        // 差异前保存位置，既有 Base 适配器整表通知不应让状态更新跳回顶部。
        if (showingHistory) { if(history.getData() != tasks) history.setData(tasks) }
        else files.setData(entries)
        val count = if(showingHistory) tasks.size else entries.size
        binding.count.text = getString(R.string.workbench_section, getString(if(showingHistory) R.string.workbench_history else R.string.workspace_files), count)
        binding.empty.isVisible = count == 0 || failed
        binding.empty.text = getString(if(failed) R.string.workbench_load_failed else if(filter.query.isNotBlank()) R.string.workspace_no_match else if(showingHistory) R.string.workbench_empty_history_body else R.string.workbench_empty_assets_body)
    }

    /** 批量流转只展示兼容全部文件的工具，无兼容项时明确提示。 */
    private fun nextTool(assets: List<WorkspaceAsset>) {
        if (assets.isEmpty()) { notice(getString(R.string.workbench_missing)); return }
        if (!WorkspaceActions.hasCompatibleTool(assets)) { notice(getString(R.string.workspace_unsupported)); return }
        WorkspaceActions.chooseTool(requireContext(), assets)
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


    /** 数据已由视图订阅，不重复创建工作区观察者。 */
    override fun initData() = Unit
    /** 解除适配器与列表引用后交给 Base 释放视图。 */
    override fun onDestroyView() { binding.list.adapter = null; lastSection = null; super.onDestroyView() }
}
