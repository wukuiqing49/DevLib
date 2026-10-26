package com.wkq.dev.demo.common

import java.io.File

/** 宿主资产流转规则，不改变独立库 API。 */
object WorkspacePolicy {
    const val ASSET_IDS = "devlib_asset_ids"
    private val images = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

    /** 将已登记的宿主页面类名映射到工具路由，未知页面返回空字符串。 */
    fun routeForActivity(name: String): String =
        when (name) {
            "OfficeActivity" -> "/document/lib_document"
            "GoogleActivity" -> "/google/core_google"
            "BaseActivity" -> "/foundation/core_base"
            "UtilsActivity" -> "/foundation/core_utils"
            "PopupActivity" -> "/foundation/lib_xpopup"
            "NetworkActivity" -> "/foundation/core_network"
            "RouterActivity" -> "/foundation/core_router_api"
            "AnnotationActivity" -> "/foundation/core_router_annotation"
            "ProcessorActivity" -> "/foundation/core_router_processor"
            "PluginActivity" -> "/foundation/core_router_gradle_plugin"
            "WatermarkActivity" -> "/image/core_image_render"
            "MetadataActivity" -> "/image/lib_metadata"
            "QrActivity" -> "/image/core_qrcode"
            "PdfActivity" -> "/document/core_pdf"
            "ArchiveActivity" -> "/files/core_archive"
            "UploadActivity" -> "/files/lib_upload"
            "TransferActivity" -> "/files/lib_transfer"
            "DropActivity" -> "/files/lib_drop"
            else -> ""
        }

    /** 清理导入名称中的路径分隔符和控制字符，限制主名长度并保留已校验的扩展名。 */
    fun importName(name: String?, extension: String): String {
        val base =
            name
                ?.substringBeforeLast('.', name)
                ?.map { if (it == '/' || it == '\\' || it == ':' || it.code < 32) '_' else it }
                ?.joinToString("")
                ?.trim('.', ' ')
                ?.take(100)
                .orEmpty()
                .ifBlank { "file" }
        return "$base.$extension"
    }

    /** 按扩展名判断工作区支持的常见图片格式，不验证文件内容。 */
    fun isImage(extension: String) = extension.lowercase() in images

    /** 按工具路由与扩展名筛选可接收的素材；未知工具默认不接收。 */
    fun accepts(route: String, extension: String): Boolean =
        when (route) {
            "/image/core_image_render",
            "/image/lib_metadata",
            "/image/core_qrcode",
            "/document/core_pdf" -> isImage(extension)
            "/files/core_archive",
            "/files/lib_upload",
            "/files/lib_transfer",
            "/files/lib_drop" -> true
            else -> false
        }

    /** 使用规范路径确认文件位于工作区根目录内，排除根目录自身及越界路径。 */
    fun managed(root: File, file: File): Boolean =
        file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) &&
            file.canonicalFile != root.canonicalFile
}
