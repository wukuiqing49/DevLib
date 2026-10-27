package com.wkq.dev.demo.document

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.wkq.dev.demo.common.DemoFiles
import com.wkq.dev.demo.common.DemoResult
import com.wkq.dev.demo.common.DemoViewModel
import com.wkq.dev.document.*
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 区块编辑状态，区块身份稳定，显示标签由模板维护。 */
data class TemplateBlockEditor(val block: RecordBlock, val enabled: Boolean, val label: String)

/** 管理宿主资料与模板编辑会话；文件操作复用公共执行器，ViewModel 不持有页面。 */
class RecordSettingsViewModel(private val saved: SavedStateHandle) : DemoViewModel() {
    val settings = MutableStateFlow<RecordSettingsData?>(null)
    val blockRevision = MutableStateFlow(0)
    val assetsChanged = MutableStateFlow(0)
    val previewRequested = MutableStateFlow(false)
    var blocks = emptyList<TemplateBlockEditor>()
        private set
    var signatures = emptyList<RecordSignature>()
        private set
    var formDraft: ArrayList<String>?
        get() = saved["draft-fields"]
        set(value) { saved["draft-fields"] = value }
    var editingSignature: Int
        get() = saved["editing-signature"] ?: -1
        set(value) { saved["editing-signature"] = value }
    var pendingAsset: String
        get() = saved["pending-asset"] ?: ""
        set(value) { saved["pending-asset"] = value }

    /** 获取当前编辑会话中的图片，不读取临时 URI。 */
    fun asset(role: String): File? = saved.get<String>("asset-$role")?.takeIf { it.isNotEmpty() }?.let(::File)

    /** 修改图片引用并通知界面，空值只清除选择，不破坏其他记录文件。 */
    fun setAsset(role: String, file: File?) {
        saved["asset-$role"] = file?.path.orEmpty()
        assetsChanged.value++
    }

    /** 首次加载默认资料与模板，旋转保留未保存会话。 */
    fun load(context: Context) {
        if (settings.value != null) return
        val draftId = saved.get<String>("draft-id")
        execute(context, record = false) {
            val loaded = RecordSettingsStore(context).load()
            val data = loaded.copy(selected = loaded.templates.firstOrNull { it.id == draftId } ?: loaded.selected)
            withContext(Dispatchers.Main) { apply(data) }
            DemoResult(context.getString(R.string.settings_ready))
        }
    }

    /** 应用一个模板和资料快照，更新可排序区块与资产引用。 */
    private fun apply(data: RecordSettingsData) {
        val t = data.selected
        val restore = saved.get<String>("draft-id") == t.id
        blocks = (t.blocks + RecordBlock.entries.filter { it !in t.blocks }).map {
            TemplateBlockEditor(it, it in t.blocks, t.labels[it].orEmpty())
        }
        if (restore) saved.get<ArrayList<android.os.Bundle>>("draft-blocks")?.let { bundles ->
            blocks = bundles.map { TemplateBlockEditor(RecordBlock.valueOf(it.getString("block")!!), it.getBoolean("enabled"), it.getString("label").orEmpty()) }
        }
        signatures = if (restore) saved.get<ArrayList<android.os.Bundle>>("draft-signatures")?.map {
            RecordSignature(it.getString("name").orEmpty(), it.getString("role").orEmpty(), it.getString("time").orEmpty(), it.getString("statement").orEmpty(), it.getString("image")?.takeIf { p -> p.isNotEmpty() }?.let(::File))
        } ?: data.signatures.toList() else data.signatures.toList()
        val previousAssets = if (restore) listOf("logo", "avatar", "watermark", "signature").associateWith { saved.get<String>("asset-$it") } else emptyMap()
        setAsset("logo", data.company.logo); setAsset("avatar", data.person.avatar)
        setAsset("watermark", t.watermark.image); setAsset("signature", null)
        if (restore) previousAssets.forEach { (role, path) -> if (path != null) setAsset(role, path.takeIf { it.isNotEmpty() }?.let(::File)) }
        else editingSignature = -1
        saved["draft-id"] = t.id
        blockRevision.value++; settings.value = data
    }

    /** 选择已有模板，调用方在切换前提示保存当前输入。 */
    fun select(template: RecordTemplate) {
        settings.value?.let { clearDraft(); apply(it.copy(selected = template)) }
    }

    /** 编辑区块显示状态与标签，输入时不重建列表或抢占焦点。 */
    fun editBlock(block: RecordBlock, enabled: Boolean? = null, label: String? = null) {
        blocks = blocks.map { if (it.block == block) it.copy(enabled = enabled ?: it.enabled, label = label ?: it.label) else it }
        persistDraft()
    }

    /** 移动区块顺序，完整保留区块及标签，不把业务顺序写死在 Activity。 */
    fun moveBlock(index: Int, delta: Int) {
        if (state.value.busy || index !in blocks.indices || index + delta !in blocks.indices) return
        blocks = blocks.toMutableList().apply { java.util.Collections.swap(this, index, index + delta) }
        blockRevision.value++
        persistDraft()
    }

    /** 新增或更新一位签署人，支持最多二十位，必须由用户明确加入签署列表。 */
    fun putSignature(signature: RecordSignature) {
        require(signature.name.isNotBlank() && signatures.size <= 20)
        signatures = if (editingSignature in signatures.indices) signatures.mapIndexed { index, old -> if (index == editingSignature) signature else old }
            else { require(signatures.size < 20); signatures + signature }
        editingSignature = -1; setAsset("signature", null); persistDraft()
    }

    /** 删除当前选中的签署人，其他签署记录保持原值。 */
    fun removeSignature() {
        require(editingSignature in signatures.indices)
        signatures = signatures.filterIndexed { index, _ -> index != editingSignature }
        editingSignature = -1; setAsset("signature", null); persistDraft()
    }

    /** 保存当前资料与模板；预览请求保存成功后才通知页面返回。 */
    fun save(context: Context, data: RecordSettingsData, preview: Boolean = false) = execute(context, record = false) {
        RecordSettingsStore(context).save(data)
        val loaded = RecordSettingsStore(context).load()
        withContext(Dispatchers.Main) {
            clearDraft()
            settings.value = null
            apply(loaded)
            if (preview) previewRequested.value = true
        }
        DemoResult(context.getString(R.string.settings_saved))
    }

    /** 复制当前编辑模板并生成独立身份，默认资料保持不变。 */
    fun copy(context: Context, data: RecordSettingsData) = execute(context, record = false) {
        val copied = data.selected.copy(id = UUID.randomUUID().toString(), revision = 1)
        RecordSettingsStore(context).save(data.copy(selected = copied))
        val loaded = RecordSettingsStore(context).load()
        withContext(Dispatchers.Main) { clearDraft(); apply(loaded) }
        DemoResult(context.getString(R.string.settings_saved))
    }

    /** 新建通用空白模板并立即保留身份，未保存编辑在重建后仍可关联同一模板。 */
    fun create(context: Context) = execute(context, record = false) {
        val current = checkNotNull(settings.value)
        val template = RecordTemplate(id = UUID.randomUUID().toString(), name = context.getString(R.string.settings_new_template),
            labels = current.selected.labels, fieldLabels = current.selected.fieldLabels)
        val store = RecordSettingsStore(context)
        store.saveTemplate(template)
        val data = store.load().copy(selected = template)
        withContext(Dispatchers.Main) { clearDraft(); apply(data) }
        DemoResult(context.getString(R.string.settings_ready))
    }

    /** 删除已选择模板并回退到剩余模板，至少保留一个。 */
    fun delete(context: Context) = execute(context, record = false) {
        val current = checkNotNull(settings.value)
        val store = RecordSettingsStore(context); store.deleteTemplate(current.selected)
        val data = store.load()
        withContext(Dispatchers.Main) { clearDraft(); apply(data) }
        DemoResult(context.getString(R.string.settings_deleted))
    }

    /** 导入外部模板，验证后按新身份保存，不覆盖本地模板或导入公司资料。 */
    fun importTemplate(context: Context, uri: Uri) = execute(context, record = false) {
        val store = RecordSettingsStore(context)
        val imported = runInterruptible { store.importTemplate(uri) }
        val data = store.load().copy(selected = imported)
        withContext(Dispatchers.Main) { clearDraft(); apply(data) }
        DemoResult(context.getString(R.string.settings_imported))
    }

    /** 导出包含水印资源的模板 JSON，通过现有结果区另存或分享，不包含个人与签名资料。 */
    fun exportTemplate(context: Context, template: RecordTemplate) = execute(context, record = false) {
        val output = DemoFiles.output(context, "json")
        output.writeText(RecordTemplateCodec.encode(template), Charsets.UTF_8)
        DemoResult(context.getString(R.string.settings_exported), output)
    }

    /** 复制并验证图片后更新指定角色，状态切回主线程再写 SavedStateHandle。 */
    fun importImage(context: Context, uri: Uri) {
        val role = pendingAsset
        execute(context, record = false) {
            require(role in setOf("logo", "avatar", "signature", "watermark"))
            val file = runInterruptible { RecordSettingsStore(context).importImage(uri) }
            withContext(Dispatchers.Main) { setAsset(role, file); pendingAsset = "" }
            DemoResult(context.getString(R.string.settings_image_ready))
        }
    }

    /** 保存轻量区块和签署草稿，页面重建不丢失尚未保存的排序或多人信息。 */
    private fun persistDraft() {
        saved["draft-blocks"] = ArrayList(blocks.map { android.os.Bundle().apply {
            putString("block", it.block.name); putBoolean("enabled", it.enabled); putString("label", it.label)
        } })
        saved["draft-signatures"] = ArrayList(signatures.map { android.os.Bundle().apply {
            putString("name", it.name); putString("role", it.role); putString("time", it.signedAt); putString("statement", it.statement); putString("image", it.image?.path)
        } })
    }

    /** 明确切换、导入或保存后丢弃旧草稿，避免覆盖新模板或已保存资料。 */
    private fun clearDraft() {
        listOf("draft-id", "draft-blocks", "draft-signatures", "draft-fields").forEach { saved.remove<Any>(it) }
    }

}
