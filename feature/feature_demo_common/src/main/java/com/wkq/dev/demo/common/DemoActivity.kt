package com.wkq.dev.demo.common

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.wkq.base.BaseUiEvent
import com.wkq.base.activity.BaseVMTitleActivity
import com.wkq.base.dialog.DialogKit
import com.wkq.dev.demo.common.databinding.DemoOutputBinding
import com.wkq.util.ShareUtil
import com.wkq.router.api.Router
import com.wkq.util.DocumentPickerHelper
import com.wkq.util.PhotoPickerHelper
import com.wkq.util.coil.ImageLoaderUtil
import kotlinx.coroutines.launch

abstract class DemoActivity<VB : ViewBinding, VM : DemoViewModel> : BaseVMTitleActivity<VB, VM>() {
    private lateinit var documents: DocumentPickerHelper
    private lateinit var photos: PhotoPickerHelper
    private val activeButtons = mutableListOf<android.view.View>()
    protected fun bindDemo(result: DemoOutputBinding, title: String, vararg buttons: android.view.View) {
        setPageTitle(title)
        activeButtons += buttons
        documents = DocumentPickerHelper.with(this).register(onOpenMultiple = { viewModel.importFiles(applicationContext, it) })
        photos = PhotoPickerHelper.with(this).register(maxItems = 20) { viewModel.importFiles(applicationContext, it) }
        result.cancel.setOnClickListener { viewModel.cancel() }
        result.share.setOnClickListener { output(false) }
        result.open.setOnClickListener { output(true) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    activeButtons.forEach { it.isEnabled = !state.busy }
                    result.progress.isVisible = state.busy
                    result.progress.isIndeterminate = state.progress == 0
                    result.progress.progress = state.progress
                    result.cancel.isVisible = state.busy
                    result.inputs.text = getString(R.string.demo_input_summary, state.inputs.size) + state.inputs.mapIndexed { i, file -> "\n${i + 1}. ${file.name} (${file.length()} B)" }.joinToString("")
                    result.message.text = state.result?.text ?: getString(if (state.busy) R.string.demo_running else R.string.demo_ready)
                    result.share.isEnabled = !state.busy && state.result?.file != null
                    result.open.isEnabled = !state.busy && state.result?.file != null
                    result.preview.isVisible = state.result?.preview != null
                    state.result?.preview?.let { ImageLoaderUtil.load(result.preview, it) }
                }
            }
        }
    }
    protected fun pickImages() { photos.launchImages() }
    protected fun pickFiles(vararg mime: String) { documents.openMultiple(*mime) }
    protected fun message(text: String) { DialogKit.message(this, getString(R.string.demo_notice), text) }
    protected fun confirm(text: String, action: () -> Unit) { DialogKit.confirm(this, getString(R.string.demo_notice), text, onConfirm = { action(); true }) }
    override fun onBaseUiEvent(event: BaseUiEvent): Boolean = if (event is BaseUiEvent.Navigate) { Router.navigate(this, event.path); true } else false
    private fun output(open: Boolean) {
        val file = viewModel.state.value.result?.file ?: return
        val mime = when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "zip" -> "application/zip"
            else -> "*/*"
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        try {
            if (open) startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            else if (!ShareUtil.shareFile(this, uri, mime, getString(R.string.demo_share))) message(getString(R.string.demo_no_reader))
        } catch (_: android.content.ActivityNotFoundException) { message(getString(R.string.demo_no_reader)) }
    }
    override fun initData() = Unit
}
