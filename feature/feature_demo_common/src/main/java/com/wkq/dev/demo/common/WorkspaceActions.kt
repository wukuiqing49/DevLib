package com.wkq.dev.demo.common

import android.content.Context
import com.lxj.xpopup.XPopup
import com.wkq.router.api.Router

/** 编排宿主工作区资产到各功能工具的路由，独立库不依赖此页面行为。 */
object WorkspaceActions {
    private val tools =
        linkedMapOf(
            "/image/core_image_render" to R.string.workspace_image,
            "/image/lib_metadata" to R.string.workspace_metadata,
            "/image/core_qrcode" to R.string.workspace_qr,
            "/document/core_pdf" to R.string.workspace_pdf,
            "/files/core_archive" to R.string.workspace_archive,
            "/files/lib_upload" to R.string.workspace_upload,
            "/files/core_nas" to R.string.workspace_nas,
            "/files/core_usb" to R.string.workspace_usb,
            "/files/lib_drop" to R.string.workspace_drop,
        )

    /** 在 UI 线程展示兼容全部所选素材的工具列表，并携带资产 ID 跳转；超过 20 个时提示并停止。 */
    fun chooseTool(context: Context, assets: List<WorkspaceAsset>) {
        if (assets.size > 20) {
            com.wkq.base.dialog.DialogKit.message(
                context,
                context.getString(R.string.demo_notice),
                context.getString(R.string.workspace_batch_limit),
            )
            return
        }
        val choices =
            tools
                .filter { (route, _) ->
                    assets.isNotEmpty() &&
                        assets.all { WorkspacePolicy.accepts(route, it.file.extension) }
                }
                .toList()
        if (choices.isEmpty()) return
        XPopup.Builder(context)
            .asBottomList(
                context.getString(R.string.workspace_continue),
                choices.map { context.getString(it.second) }.toTypedArray(),
            ) { index, _ ->
                Router.navigate(
                    context,
                    Router.build(choices[index].first)
                        .withStringArrayList(
                            WorkspacePolicy.ASSET_IDS,
                            ArrayList(assets.map { it.id }),
                        ),
                )
            }
            .show()
    }
}
