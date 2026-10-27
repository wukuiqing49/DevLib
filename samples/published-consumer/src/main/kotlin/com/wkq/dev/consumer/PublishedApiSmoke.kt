package com.wkq.dev.consumer

import com.lxj.xpopup.XPopup
import com.wkq.router.api.Router
import com.wkq.droplan.drop.ShareEngine
import com.wkq.roof.export.excel.SimpleXlsxWriter
import retrofit2.Retrofit

/** 必须通过 POM 获得 XPopup、路由注解及 Retrofit，不重复声明第三方依赖。 */
class PublishedApiSmoke(
 val sharing: ShareEngine,
 val retrofit: Retrofit,
) {
 val router = Router
 val recordExporter = com.wkq.dev.document.RecordExporter
 val record = com.wkq.dev.document.ExportRecord("Consumer record")
 val documentWriter = SimpleXlsxWriter
 val generatedRoutes = com.wkq.router.generated.RouterPaths_DevLibPublishedConsumer
 fun popup(context: android.content.Context) = XPopup.Builder(context)
}

abstract class BaseConsumer<VB : androidx.viewbinding.ViewBinding> : com.wkq.base.activity.BaseActivity<VB>()

@com.wkq.router.annotation.Route(path = "/consumer/smoke")
class PublishedRouteActivity : android.app.Activity()
