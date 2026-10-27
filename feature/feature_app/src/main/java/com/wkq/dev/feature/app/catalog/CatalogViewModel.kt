package com.wkq.dev.feature.app.catalog

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.wkq.base.BaseViewModel
import com.wkq.dev.demo.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** 保存子页面搜索词、分类和文件/历史选择，以便页面重建时恢复筛选状态。 */
data class CatalogFilter(
    val query: String = "",
    val category: String = "",
    val section: String = "files",
)

/** 工作台的 BaseViewModel，管理可恢复筛选、最多 20 个资产选择及工作区快照订阅。 */
class CatalogViewModel(private val saved: SavedStateHandle) : BaseViewModel() {
    private val mutable =
        MutableStateFlow(
            CatalogFilter(saved["query"] ?: "", saved["category"] ?: "", saved["section"] ?: "files")
        )
    val filter = mutable.asStateFlow()
    private val data =
        MutableStateFlow(WorkspaceSnapshot(emptyList(), emptyList(), emptyList(), emptySet()))
    val snapshot = data.asStateFlow()
    private val selected =
        MutableStateFlow(saved.get<ArrayList<String>>("selected")?.toSet().orEmpty())
    val selection = selected.asStateFlow()

    /** 切换资产选择状态，新增选择最多 20 个，并同步 SavedStateHandle。 */
    fun select(id: String): Boolean {
        val values = selected.value.toMutableSet()
        if (!values.remove(id)) { if (values.size >= 20) return false; values.add(id) }
        selected.value = values
        saved["selected"] = ArrayList(values)
        return true
    }

    /** 清空批量选择及其可恢复状态，不删除工作区资产。 */
    fun clearSelection() {
        selected.value = emptySet()
        saved["selected"] = arrayListOf<String>()
    }

    private val working = MutableStateFlow(false)
    val importing = working.asStateFlow()
    private val failed = MutableStateFlow(false)
    val loadFailed = failed.asStateFlow()
    private var observation: Job? = null

    /** 只建立一个工作区版本订阅，在 IO 读取快照；失败设置加载错误，取消继续向上传播。 */
    fun attach(context: Context) {
        if (observation?.isActive == true) return
        failed.value = false
        observation =
            viewModelScope.launch {
                try {
                    val store =
                        withContext(Dispatchers.IO) {
                            WorkspaceStore.get(context.applicationContext)
                        }
                    store.revision.collect {
                        data.value = withContext(Dispatchers.IO) { store.snapshot() }
                        val valid = data.value.assets.map { asset -> asset.id }.toSet()
                        if (!valid.containsAll(selected.value)) {
                            selected.value = selected.value.intersect(valid)
                            saved["selected"] = ArrayList(selected.value)
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    failed.value = true
                }
            }
    }

    /** 更新搜索词并保存到 SavedStateHandle，供筛选与重建恢复使用。 */
    fun search(query: String) {
        mutable.value = mutable.value.copy(query = query)
        saved["query"] = query
        saved["query-${mutable.value.section}"] = query
    }

    /** 更新分类筛选并持久到 SavedStateHandle。 */
    fun category(category: String) {
        mutable.value = mutable.value.copy(category = category)
        saved["category"] = category
    }

    /** 切换工作区文件/历史子页，保留搜索与文件分类，不影响其他顶层页面。 */
    fun section(section: String) {
        mutable.value = mutable.value.copy(section = section, query = saved["query-$section"] ?: "")
        saved["query"] = mutable.value.query
        saved["section"] = section
    }

    /** 在 ViewModel 的 IO 协程中切换工具收藏状态。 */
    fun favorite(context: Context, route: String) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { WorkspaceStore.get(context.applicationContext).favorite(route) } }
            catch(cancel: CancellationException) { throw cancel }
            catch(_: Exception) { sendToast(context.getString(com.wkq.dev.feature.app.R.string.workbench_load_failed)) }
        }
    }

    /** 在 ViewModel 的 IO 协程中记录工具最近使用顺序。 */
    fun visit(context: Context, route: String) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { WorkspaceStore.get(context.applicationContext).visit(route) } }
            catch(cancel: CancellationException) { throw cancel }
            catch(_: Exception) { sendToast(context.getString(com.wkq.dev.feature.app.R.string.workbench_load_failed)) }
        }
    }

    /** 防止重复导入，在 IO 复制 Uri 文件；普通失败回调 UI 提示，取消继续传播并始终复位忙碌状态。 */
    fun import(context: Context, uris: List<android.net.Uri>, onError: () -> Unit) {
        if (working.value || uris.isEmpty()) return
        working.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    DemoFiles.importFiles(context.applicationContext, uris)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                onError()
            } finally {
                working.value = false
            }
        }
    }
}
