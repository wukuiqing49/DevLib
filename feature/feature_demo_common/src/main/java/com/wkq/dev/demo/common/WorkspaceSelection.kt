package com.wkq.dev.demo.common

import java.io.FileNotFoundException

/** 按请求顺序解析整批资产，拒绝重复、超量、缺失和类型不匹配；仅全部有效才返回，调用方在 IO 执行。 */
internal fun resolveWorkspaceAssets(
    ids: List<String>,
    route: String,
    find: (String) -> WorkspaceAsset?,
): List<WorkspaceAsset> {
    require(ids.size in 1..20 && ids.distinct().size == ids.size)
    val assets = ids.map { id ->
        find(id)?.takeIf { it.file.isFile } ?: throw FileNotFoundException()
    }
    require(assets.all { WorkspacePolicy.accepts(route, it.file.extension) })
    return assets
}
