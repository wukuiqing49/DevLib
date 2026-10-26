package com.wkq.dev.demo.files

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.content.FileProvider
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.files.databinding.DemoUploadBinding
import com.wkq.iptc.upload.*
import com.wkq.iptc.upload.http.HttpUploader
import com.wkq.router.annotation.Route
import java.net.URI
import kotlinx.coroutines.withTimeout

/** 上传宿主页，负责协议表单、连接测试及用户确认后的文件上传。 */
@Route(path = "/files/lib_upload")
class UploadActivity : DemoActivity<DemoUploadBinding, UploadViewModel>() {
    private val protocols =
        listOf(UploadProtocolType.FTPS, UploadProtocolType.FTP, UploadProtocolType.HTTP)

    /** 绑定协议选项、文件选择和连接测试，上传前执行确认及端点权限检查。 */
    override fun initView() {
        val b = contentBinding
        bindDemo(b.result, getString(R.string.demo_upload_title), b.choose, b.test, b.upload)
        b.protocol.adapter =
            ArrayAdapter.createFromResource(
                this,
                R.array.demo_upload_protocols,
                android.R.layout.simple_spinner_dropdown_item,
            )
        b.protocol.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                /** 没有协议选择时保留现有输入，等待用户选择有效协议。 */
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit

                /** 按 HTTP 或 FTP 系列更新默认端口和说明，保留用户自定义的非默认端口。 */
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val http = protocols[position] == UploadProtocolType.HTTP
                    if (
                        b.port.text.isNullOrBlank() || b.port.text.toString() in listOf("21", "443")
                    ) {
                        b.port.setText(if (http) "443" else "21")
                    }
                    b.protocolHelp.setText(
                        if (http) R.string.demo_upload_https_help else R.string.demo_upload_ftp_help
                    )
                }
            }
        b.choose.setOnClickListener { pickFiles("*/*") }
        b.test.setOnClickListener {
            val target = draft()
            withNetworkEndpointPermission(target.host) { viewModel.submit(applicationContext, target, false) }
        }
        b.upload.setOnClickListener {
            val draft = draft()
            confirm(getString(R.string.demo_upload_confirm)) {
                withNetworkEndpointPermission(draft.host) { viewModel.submit(applicationContext, draft, true) }
            }
        }
    }

    /** 读取当前协议与服务器表单为上传草稿，不向日志或历史输出凭据。 */
    private fun draft() =
        UploadDraft(
            protocols[contentBinding.protocol.selectedItemPosition.coerceAtLeast(0)],
            contentBinding.host.text.toString().trim(),
            contentBinding.port.text.toString(),
            contentBinding.user.text.toString().trim(),
            contentBinding.password.text.toString(),
            contentBinding.directory.text.toString().trim(),
        )
}

/** 一次上传配置的宿主输入，含协议、地址、端口、用户、密码与目录，后续提交时统一校验。 */
data class UploadDraft(
    val protocol: UploadProtocolType,
    val host: String,
    val port: String,
    val user: String,
    val password: String,
    val directory: String,
)

/** 通过统一上传分发器测试和上传，记录已测试配置以限制未验证端点提交。 */
class UploadViewModel : DemoViewModel() {
    private var testedConfig: UploadServerConfig? = null

    /** 在 IO 校验协议与地址；连接测试超时为 20 秒，实际上传必须匹配已测试配置，并发布进度和错误码。 */
    fun submit(context: Context, draft: UploadDraft, upload: Boolean) =
        execute(context) {
            val port = draft.port.toIntOrNull()
            if (draft.host.isBlank() || port == null || port !in 1..65535) {
                throw DemoInputException(context.getString(R.string.demo_upload_invalid_host))
            }
            if (draft.protocol == UploadProtocolType.HTTP) {
                val uri = runCatching { URI(draft.host) }.getOrNull()
                if (
                    uri?.scheme != "https" ||
                        uri.host.isNullOrBlank() ||
                        uri.userInfo != null ||
                        uri.query != null ||
                        uri.fragment != null ||
                        (uri.port != -1 && uri.port != port)
                ) {
                    throw DemoInputException(context.getString(R.string.demo_upload_invalid_https))
                }
            } else if (
                draft.host.contains("://") ||
                    draft.host.contains('/') ||
                    draft.user.isBlank() ||
                    draft.password.isBlank()
            ) {
                throw DemoInputException(context.getString(R.string.demo_upload_invalid_ftp))
            }
            val config: UploadServerConfig =
                when (draft.protocol) {
                    UploadProtocolType.FTP ->
                        FtpConfig(draft.host, port, draft.user, draft.password, draft.directory)
                    UploadProtocolType.FTPS ->
                        FtpsConfig(draft.host, port, draft.user, draft.password, draft.directory)
                    UploadProtocolType.HTTP ->
                        HttpConfig(draft.host, port, draft.user, draft.password, draft.directory)
                }
            val profile = UploadServerProfile(protocol = draft.protocol, config = config)
            if (!profile.isReady())
                throw DemoInputException(context.getString(R.string.demo_upload_invalid_host))
            if (upload && config != testedConfig)
                throw DemoInputException(context.getString(R.string.demo_upload_test_first))
            val dispatcher =
                UploadDispatcher(
                    listOf(FtpUploader(context), FtpsUploader(context), HttpUploader(context))
                )
            val result =
                if (upload) {
                    val file = input()
                    val uri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                    dispatcher.upload(UploadTask(0, uri.toString(), file.name), profile) {
                        done,
                        total ->
                        progress(
                            (done.toDouble() * 100 / total.coerceAtLeast(1))
                                .toInt()
                                .coerceIn(0, 100)
                        )
                    }
                } else {
                    testedConfig = null
                    withTimeout(20_000) { dispatcher.testConnection(profile) }
                }
            when (result) {
                is UploadResult.Success -> {
                    if (!upload) testedConfig = config
                    DemoResult(
                        context.getString(
                            if (upload) R.string.demo_upload_success
                            else R.string.demo_upload_connected
                        )
                    )
                }
                is UploadResult.Failure ->
                    DemoResult(
                        context.getString(R.string.demo_upload_failure, result.error.code.name)
                    )
            }
        }
}
