package com.wkq.droplan.html

import android.content.res.AssetManager
import java.io.InputStream

data class WebAsset(val assetPath: String, val contentType: String)

/** 仅映射预置静态资源；请求参数应由协议层解析，不接受任意磁盘路径。 */
object WebAssetCatalog {
    fun resolve(requestPath: String): WebAsset? = when (requestPath) {
        "/", "/index.html" -> WebAsset("droplan_web/index.html", "text/html; charset=utf-8")
        "/assets/app.css" -> WebAsset("droplan_web/app.css", "text/css; charset=utf-8")
        "/assets/app.js" -> WebAsset("droplan_web/app.js", "application/javascript; charset=utf-8")
        "/assets/zh.json" -> WebAsset("droplan_web/zh.json", "application/json; charset=utf-8")
        "/assets/en.json" -> WebAsset("droplan_web/en.json", "application/json; charset=utf-8")
        "/assets/ja.json" -> WebAsset("droplan_web/ja.json", "application/json; charset=utf-8")
        "/assets/ko.json" -> WebAsset("droplan_web/ko.json", "application/json; charset=utf-8")
        "/assets/de.json" -> WebAsset("droplan_web/de.json", "application/json; charset=utf-8")
        "/assets/fr.json" -> WebAsset("droplan_web/fr.json", "application/json; charset=utf-8")
        "/assets/es.json" -> WebAsset("droplan_web/es.json", "application/json; charset=utf-8")
        "/assets/pt-BR.json" -> WebAsset("droplan_web/pt-BR.json", "application/json; charset=utf-8")
        else -> null
    }
}

class OfflineWebAssets(private val assets: AssetManager) {
    /** 返回流由 HTTP 响应发送方关闭。 */
    fun open(asset: WebAsset): InputStream = assets.open(asset.assetPath, AssetManager.ACCESS_STREAMING)
}
