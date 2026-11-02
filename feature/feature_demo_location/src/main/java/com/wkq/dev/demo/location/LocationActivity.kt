package com.wkq.dev.demo.location

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import com.wkq.dev.demo.common.DemoActivity
import com.wkq.dev.demo.common.DemoViewModel
import com.wkq.dev.demo.location.databinding.DemoLocationBinding
import com.wkq.location.LocationKit
import com.wkq.location.address.LocationConfig
import com.wkq.location.address.LocationType
import com.wkq.router.annotation.Route

/** 定位宿主示例，权限、用户操作和页面生命周期由宿主负责，不自动启动后台服务。 */
@Route(path = "/location/core_location")
class LocationActivity : DemoActivity<DemoLocationBinding, LocationViewModel>() {
    private var collecting = false
    private var requestGeneration = 0L
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission()) startLocation() else viewModel.publish(getString(R.string.location_denied))
    }

    /** 复用 Demo 的 Base 标题、唯一 Insets owner 和结果区，不展示文件输入操作。 */
    override fun initView() {
        val b = contentBinding
        bindDemo(b.result, getString(R.string.location_title))
        b.result.inputs.visibility = View.GONE
        b.start.setOnClickListener {
            if (collecting) return@setOnClickListener
            if (hasLocationPermission()) startLocation() else confirm(getString(R.string.location_permission_reason)) {
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
        b.stop.setOnClickListener { stopLocation(true) }
        viewModel.restore()
    }

    /** 接受精确或近似位置授权，拒绝时保留再次尝试的显式入口。 */
    private fun hasLocationPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /** 显式启动单次定位；忽略过期回调，默认坐标不作为成功位置展示。 */
    private fun startLocation() {
        if (collecting) return
        collecting = true
        val generation = ++requestGeneration
        contentBinding.start.isEnabled = false
        viewModel.publish(getString(R.string.location_waiting))
        LocationKit.init(applicationContext, LocationConfig().setLocationType(LocationType.SINGLE).setTimeout(10_000L))
        LocationKit.startLocation { result ->
            if (!collecting || generation != requestGeneration) return@startLocation
            collecting = false
            contentBinding.start.isEnabled = true
            val location = result.location
            viewModel.publish(if (result.success && location != null && location.provider != "default") {
                getString(R.string.location_result, location.latitude, location.longitude, location.accuracy)
            } else getString(R.string.location_failed))
        }
    }

    /** 清理本页拥有的请求；停止和离开页面后不继续回调或后台采集。 */
    private fun stopLocation(showFeedback: Boolean) {
        val wasCollecting = collecting
        collecting = false
        requestGeneration++
        if (wasCollecting) LocationKit.stopLocation()
        contentBinding.start.isEnabled = true
        if (showFeedback || wasCollecting) viewModel.publish(getString(R.string.location_stopped))
    }

    /** 页面不可见时取消定位，重建后仅恢复结果，不自动重发权限或定位请求。 */
    override fun onStop() {
        stopLocation(false)
        super.onStop()
    }
}

/** 保存文字结果以支持页面重建，定位回调不持有 ViewModel 或 Activity 的长期引用。 */
class LocationViewModel(private val saved: SavedStateHandle) : DemoViewModel() {
    /** 发布并保存当前结果，避免重建后自动重新执行定位。 */
    fun publish(text: String) { saved["location-result"] = text; report(text) }
    /** 仅恢复已有结果，不启动采集。 */
    fun restore() { saved.get<String>("location-result")?.let(::report) }
}
