package com.wkq.dev.demo.location

import android.content.Context
import android.view.View
import androidx.lifecycle.SavedStateHandle
import com.wkq.address.GeoDbHelper
import com.wkq.dev.demo.common.DemoActivity
import com.wkq.dev.demo.common.DemoResult
import com.wkq.dev.demo.common.DemoViewModel
import com.wkq.dev.demo.location.databinding.DemoAddressBinding
import com.wkq.router.annotation.Route

/** 离线地址独立示例，仅查询用户输入的坐标，不请求定位或网络权限。 */
@Route(path = "/location/core_location_address")
class AddressActivity : DemoActivity<DemoAddressBinding, AddressViewModel>() {
    /** 复用 Demo 表单与结果区；输入由系统 View 状态保存，不硬编码默认位置。 */
    override fun initView() {
        val b = contentBinding
        bindDemo(b.result, getString(R.string.address_title), b.query)
        b.result.inputs.visibility = View.GONE
        b.query.setOnClickListener {
            viewModel.query(applicationContext, b.latitude.text?.toString().orEmpty(), b.longitude.text?.toString().orEmpty())
        }
        viewModel.restore()
    }
}

/** 在 Demo 的 IO 任务机制内复制数据库并查询，帮助类按次关闭，不持有页面 Context。 */
class AddressViewModel(private val saved: SavedStateHandle) : DemoViewModel() {
    /** 校验范围和有限数值，查询空结果与失败均有可重试反馈。 */
    fun query(context: Context, latitude: String, longitude: String) {
        val lat = latitude.toDoubleOrNull()
        val lng = longitude.toDoubleOrNull()
        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            report(context.getString(R.string.address_invalid)); return
        }
        execute(context, record = false) {
            val nearest = GeoDbHelper(context.applicationContext).use { it.getNearestPlaceResult(lat, lng) }
            val text = if (nearest == null) context.getString(R.string.address_empty)
                else context.getString(R.string.address_result, nearest.toDisplayString(), nearest.distanceKm)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { saved["address-result"] = text }
            DemoResult(text)
        }
    }

    /** 页面重建仅恢复文字结果，不重复复制数据库或查询。 */
    fun restore() { saved.get<String>("address-result")?.let(::report) }
}
