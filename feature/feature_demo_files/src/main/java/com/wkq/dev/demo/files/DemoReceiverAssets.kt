package com.wkq.dev.demo.files

import android.content.Context
import com.wkq.droplan.drop.StaticAsset
import com.wkq.droplan.drop.StaticAssetProvider

/** Demo 自带最小接收页面，不依赖原 DropLAN 产品网页库。 */
internal object DemoReceiverAssets {
    /** 只映射首页和接收脚本到宿主 assets，未知路径返回空；资源流由服务端按需打开。 */
    fun provider(context: Context) = StaticAssetProvider { path ->
        when (path) {
            "/",
            "/index.html" -> "index.html" to "text/html; charset=utf-8"
            "/receiver.js" -> "receiver.js" to "application/javascript; charset=utf-8"
            else -> null
        }?.let { (name, type) ->
            StaticAsset(type, null) { context.assets.open("demo_receiver/$name") }
        }
    }
}
