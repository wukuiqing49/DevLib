package com.wkq.dev.feature.app.catalog

import com.wkq.dev.feature.app.R

/** 目录使用简短的库名称与能力摘要，完整流程说明保留在详情页。 */
object DirectoryPresentation {
    /** 根据登记模块返回本地化标题，未知模块回退原始目录名称。 */
    fun title(entry: DemoEntry): Int = when(entry.module) {
        "core_base" -> R.string.directory_base
        "core_utils" -> R.string.directory_utils
        "core_network" -> R.string.directory_network
        "core_router_api" -> R.string.directory_router
        "lib_xpopup" -> R.string.directory_popup
        "core_google" -> R.string.directory_google
        "core_image_render" -> R.string.directory_image
        "core_qrcode" -> R.string.directory_qr
        "lib_metadata" -> R.string.directory_metadata
        "core_document" -> R.string.directory_document
        "core_archive" -> R.string.directory_archive
        "lib_upload" -> R.string.directory_upload
        "core_nas" -> R.string.directory_nas
        "core_usb" -> R.string.directory_usb
        "lib_drop" -> R.string.directory_drop
        else -> entry.title
    }
    /** 描述真实能力而非演示操作步骤，避免长技术说明占满目录。 */
    fun summary(entry: DemoEntry): Int = when(entry.module) {
        "core_base" -> R.string.summary_base
        "core_utils" -> R.string.summary_utils
        "core_network" -> R.string.summary_network
        "core_router_api" -> R.string.summary_router
        "lib_xpopup" -> R.string.summary_popup
        "core_google" -> R.string.summary_google
        "core_image_render" -> R.string.summary_image
        "core_qrcode" -> R.string.summary_qr
        "lib_metadata" -> R.string.summary_metadata
        "core_document" -> R.string.summary_document
        "core_archive" -> R.string.summary_archive
        "lib_upload" -> R.string.summary_upload
        "core_nas" -> R.string.summary_nas
        "core_usb" -> R.string.summary_usb
        "lib_drop" -> R.string.summary_drop
        else -> entry.intro
    }
}
