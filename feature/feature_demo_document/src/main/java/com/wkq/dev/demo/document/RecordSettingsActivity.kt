package com.wkq.dev.demo.document

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wkq.base.adapter.BaseRecyclerViewAdapter
import com.wkq.dev.demo.common.DemoActivity
import com.wkq.dev.demo.document.databinding.DemoRecordSettingsBinding
import com.wkq.dev.demo.document.databinding.ItemTemplateBlockBinding
import com.wkq.dev.document.*
import com.wkq.util.coil.ImageLoaderUtil
import kotlinx.coroutines.launch

/** 基于 Base 的资料、多人签署、模板和水印编辑页，文件读写交由 ViewModel。 */
class RecordSettingsActivity : DemoActivity<DemoRecordSettingsBinding, RecordSettingsViewModel>() {
    private lateinit var blockAdapter: BaseRecyclerViewAdapter<ItemTemplateBlockBinding, TemplateBlockEditor>
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importImage(applicationContext, uri)
    }
    private val templatePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importTemplate(applicationContext, uri)
    }

    /** 绑定模板管理、资料与签署流程，复用 Base 列表、确认弹窗和公共结果区。 */
    override fun initView() {
        val b = contentBinding
        bindDemo(b.result, getString(R.string.settings_title), b.save, b.preview, b.copyTemplate,
            b.newTemplate, b.deleteTemplate, b.importTemplate, b.exportTemplate)
        b.watermarkKind.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.settings_watermark_none), getString(R.string.settings_watermark_text), getString(R.string.settings_watermark_image)))
        blockAdapter = object : BaseRecyclerViewAdapter<ItemTemplateBlockBinding, TemplateBlockEditor>(this, ItemTemplateBlockBinding::inflate) {
            /** 绑定稳定区块身份、显示标签和排序按钮，移除复用输入框的旧监听。 */
            override fun convert(binding: ItemTemplateBlockBinding, item: TemplateBlockEditor, position: Int) {
                binding.enabledBlock.setOnCheckedChangeListener(null)
                binding.enabledBlock.text = blockName(item.block)
                binding.enabledBlock.isChecked = item.enabled
                binding.enabledBlock.setOnCheckedChangeListener { _, enabled -> viewModel.editBlock(item.block, enabled = enabled) }
                (binding.label.tag as? android.text.TextWatcher)?.let { binding.label.removeTextChangedListener(it) }
                binding.label.setText(item.label)
                binding.label.tag = binding.label.doAfterTextChanged { viewModel.editBlock(item.block, label = it.toString()) }
                binding.up.isEnabled = position > 0
                binding.down.isEnabled = position < viewModel.blocks.lastIndex
                binding.up.setOnClickListener { viewModel.moveBlock(position, -1) }
                binding.down.setOnClickListener { viewModel.moveBlock(position, 1) }
            }
        }
        listOf(b.toggleLayout to b.groupLayout, b.toggleWatermark to b.groupWatermark, b.toggleCompany to b.groupCompany,
            b.togglePerson to b.groupPerson, b.toggleSignatures to b.groupSignatures).forEach { (button, group) ->
            button.setOnClickListener { group.visibility = if (group.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        b.blocks.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        b.blocks.adapter = blockAdapter
        b.chooseTemplate.setOnClickListener {
            val data = viewModel.settings.value ?: return@setOnClickListener
            confirm(getString(R.string.settings_confirm_switch)) {
                com.lxj.xpopup.XPopup.Builder(this).asBottomList(getString(R.string.settings_choose_template),
                    data.templates.map { it.name }.toTypedArray()) { index, _ -> viewModel.select(data.templates[index]) }.show()
            }
        }
        b.save.setOnClickListener { editAction { viewModel.save(applicationContext, readEditor()) } }
        b.preview.setOnClickListener { editAction { viewModel.save(applicationContext, readEditor(), preview = true) } }
        b.newTemplate.setOnClickListener { confirm(getString(R.string.settings_confirm_switch)) { viewModel.create(applicationContext) } }
        b.copyTemplate.setOnClickListener { editAction { viewModel.copy(applicationContext, readEditor()) } }
        b.deleteTemplate.setOnClickListener { confirm(getString(R.string.settings_confirm_delete)) { viewModel.delete(applicationContext) } }
        b.importTemplate.setOnClickListener {
            confirm(getString(R.string.settings_confirm_switch)) { templatePicker.launch("application/json") }
        }
        b.exportTemplate.setOnClickListener { editAction { viewModel.exportTemplate(applicationContext, readEditor().selected) } }
        listOf("logo" to b.logoPick, "avatar" to b.avatarPick, "signature" to b.signaturePick, "watermark" to b.watermarkPick).forEach { (role, button) ->
            button.setOnClickListener { viewModel.pendingAsset = role; imagePicker.launch("image/*") }
        }
        listOf("logo" to b.logoClear, "avatar" to b.avatarClear, "signature" to b.signatureClear, "watermark" to b.watermarkClear).forEach { (role, button) ->
            button.setOnClickListener { viewModel.setAsset(role, null) }
        }
        b.addSigner.setOnClickListener { editAction {
            viewModel.putSignature(RecordSignature(b.signer.text.toString(), b.signerRole.text.toString(), b.signedAt.text.toString(), b.statement.text.toString(), viewModel.asset("signature")))
            clearSigner()
        } }
        b.newSigner.setOnClickListener { viewModel.editingSignature = -1; viewModel.setAsset("signature", null); clearSigner() }
        b.chooseSigner.setOnClickListener {
            if (viewModel.signatures.isNotEmpty()) com.lxj.xpopup.XPopup.Builder(this)
                .asBottomList(getString(R.string.settings_choose_signer), viewModel.signatures.map { it.name }.toTypedArray()) { index, _ ->
                    viewModel.editingSignature = index
                    val item = viewModel.signatures[index]
                    b.signer.setText(item.name); b.signerRole.setText(item.role); b.signedAt.setText(item.signedAt); b.statement.setText(item.statement)
                    viewModel.setAsset("signature", item.image)
                }.show()
        }
        b.removeSigner.setOnClickListener { confirm(getString(R.string.settings_confirm_signer)) { editAction { viewModel.removeSignature(); clearSigner() } } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.settings.collect { it?.let(::fillEditor) } }
                launch { viewModel.blockRevision.collect { blockAdapter.setData(viewModel.blocks) } }
                launch { viewModel.assetsChanged.collect { updateAssets() } }
                launch { viewModel.state.collect { setEnabled(b.editor, !it.busy) } }
                launch { viewModel.previewRequested.collect { if (it) { setResult(Activity.RESULT_OK, Intent().putExtra("preview", true)); finish() } } }
            }
        }
        viewModel.load(applicationContext)
    }

    /** 保存未提交表单，旋转与进程重建时恢复；区块与签署列表另由 ViewModel 保存。 */
    override fun onSaveInstanceState(outState: Bundle) {
        viewModel.formDraft = ArrayList(inputs().map { it.text.toString() } + listOf(contentBinding.landscape.isChecked.toString(),
            contentBinding.pageNumbers.isChecked.toString(), contentBinding.everyPage.isChecked.toString(), contentBinding.watermarkKind.selectedItemPosition.toString()))
        super.onSaveInstanceState(outState)
    }

    /** 捕获当前编辑配置；解析失败交由统一的本地化输入提示。 */
    private fun readEditor(): RecordSettingsData {
        val b = contentBinding
        val current = checkNotNull(viewModel.settings.value)
        val style = RecordStyle(b.primary.text.toString().removePrefix("#"), b.fill.text.toString().removePrefix("#"),
            b.bodySize.text.toString().toInt(), b.titleSize.text.toString().toInt(), b.margin.text.toString().toInt(),
            b.landscape.isChecked, b.header.text.toString(), b.footer.text.toString(), b.pageNumbers.isChecked)
        val watermark = RecordWatermark(WatermarkKind.entries[b.watermarkKind.selectedItemPosition], b.watermarkText.text.toString(), viewModel.asset("watermark"),
            b.opacity.text.toString().toInt(), b.rotation.text.toString().toInt(), b.width.text.toString().toInt(),
            b.positionX.text.toString().toInt(), b.positionY.text.toString().toInt(), b.everyPage.isChecked)
        val template = current.selected.copy(revision = current.selected.revision + 1, name = b.templateName.text.toString(),
            blocks = viewModel.blocks.filter { it.enabled }.map { it.block }, labels = viewModel.blocks.associate { it.block to it.label },
            detailColumns = b.columns.text.toString().takeIf { it.isNotBlank() }?.split(',', '，')?.map { it.trim().toInt() - 1 }.orEmpty(),
            fieldNames = b.fieldNames.text.toString().lines().filter { it.isNotBlank() }, imageColumns = b.imageColumns.text.toString().toInt(), style = style, watermark = watermark)
        template.validate()
        return current.copy(selected = template,
            company = RecordCompany(b.companyName.text.toString(), b.address.text.toString(), b.companyPhone.text.toString(), b.companyEmail.text.toString(), b.website.text.toString(), b.registration.text.toString(), viewModel.asset("logo")),
            person = RecordPerson(b.personName.text.toString(), b.personRole.text.toString(), b.organization.text.toString(), b.personPhone.text.toString(), b.personEmail.text.toString(), viewModel.asset("avatar")),
            signatures = viewModel.signatures.toList())
    }

    /** 将模板和默认资料填入表单，若有当前会话草稿则优先恢复草稿。 */
    private fun fillEditor(data: RecordSettingsData) {
        val b = contentBinding; val t = data.selected; val s = t.style; val w = t.watermark; val c = data.company; val p = data.person
        val values = listOf(t.name, s.primaryColor, s.headerFill, s.bodySize.toString(), s.titleSize.toString(), s.marginPoints.toString(),
            s.header, s.footer, t.detailColumns.joinToString(",") { (it + 1).toString() }, t.fieldNames.joinToString("\n"), t.imageColumns.toString(),
            w.text, w.opacityPercent.toString(), w.rotationDegrees.toString(), w.widthPercent.toString(), w.xPercent.toString(), w.yPercent.toString(),
            c.name, c.address, c.phone, c.email, c.website, c.registrationNumber, p.name, p.role, p.organization, p.phone, p.email, "", "", "", "")
        val draft = viewModel.formDraft
        inputs().forEachIndexed { index, input -> input.setText(draft?.getOrNull(index) ?: values[index]) }
        val offset = inputs().size
        b.landscape.isChecked = draft?.getOrNull(offset)?.toBooleanStrictOrNull() ?: s.landscape
        b.pageNumbers.isChecked = draft?.getOrNull(offset + 1)?.toBooleanStrictOrNull() ?: s.showPageNumbers
        b.everyPage.isChecked = draft?.getOrNull(offset + 2)?.toBooleanStrictOrNull() ?: w.everyPage
        b.watermarkKind.setSelection(draft?.getOrNull(offset + 3)?.toIntOrNull()?.coerceIn(0, 2) ?: w.kind.ordinal)
        blockAdapter.setData(viewModel.blocks)
        updateAssets()
    }

    /** 固定字段顺序用于轻量草稿恢复，不将 View 或 Context 保存进 ViewModel。 */
    private fun inputs() = contentBinding.let { b -> listOf(b.templateName, b.primary, b.fill, b.bodySize, b.titleSize, b.margin,
        b.header, b.footer, b.columns, b.fieldNames, b.imageColumns, b.watermarkText, b.opacity, b.rotation, b.width, b.positionX, b.positionY,
        b.companyName, b.address, b.companyPhone, b.companyEmail, b.website, b.registration, b.personName, b.personRole, b.organization,
        b.personPhone, b.personEmail, b.signer, b.signerRole, b.signedAt, b.statement) }

    /** 用统一图片加载器展示当前角色选择，空引用立即清空预览。 */
    private fun updateAssets() {
        val b = contentBinding
        listOf("logo" to b.logoPreview, "avatar" to b.avatarPreview, "signature" to b.signaturePreview, "watermark" to b.watermarkPreview).forEach { (role, image) ->
            val file = viewModel.asset(role); image.visibility = if (file == null) View.GONE else View.VISIBLE
            if (file != null) ImageLoaderUtil.load(image, file) else image.setImageDrawable(null)
        }
        b.signersCount.text = getString(R.string.settings_signers_count, viewModel.signatures.size)
    }

    /** 清空签署人编辑框，已加入列表的签署快照保持不变。 */
    private fun clearSigner() {
        val b = contentBinding
        listOf(b.signer, b.signerRole, b.signedAt, b.statement).forEach { it.setText("") }
        updateAssets()
    }

    /** 将区块身份映射到本地化名称，不直接显示枚举。 */
    private fun blockName(block: RecordBlock): String = getString(listOf(R.string.settings_block_title, R.string.settings_block_company,
        R.string.settings_block_person, R.string.settings_block_fields, R.string.settings_block_notes, R.string.settings_block_details,
        R.string.settings_block_images, R.string.settings_block_signatures)[block.ordinal])

    /** 输入解析或签署编辑失败时显示可操作的本地化错误，不把底层异常直接暴露给用户。 */
    private fun editAction(action: () -> Unit) {
        if (viewModel.state.value.busy) return
        try { action() } catch (_: IllegalArgumentException) { viewModel.report(getString(R.string.settings_invalid)) }
        catch (_: IllegalStateException) { viewModel.report(getString(R.string.settings_invalid)) }
    }

    /** 忙碌时递归禁用表单，保证导入或保存期间输入快照稳定。 */
    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (index in 0 until view.childCount) setEnabled(view.getChildAt(index), enabled)
    }
}
