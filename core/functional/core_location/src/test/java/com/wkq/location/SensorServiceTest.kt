package com.wkq.location

import android.Manifest
import com.wkq.location.sensor.SensorService
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 验证前台定位服务缺少运行时权限时安全停止，不注册位置或发布采集通知。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class SensorServiceTest {
    /** 未授权启动在新旧系统均不得进入前台采集，也不能在销毁时崩溃。 */
    @Test fun missingLocationPermissionStopsService() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val controller = Robolectric.buildService(SensorService::class.java).create()
        val service = shadowOf(controller.get())
        assertTrue(service.isStoppedBySelf)
        assertNull(service.lastForegroundNotification)
        controller.destroy()
    }
}
