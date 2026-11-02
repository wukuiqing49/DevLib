package com.wkq.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import com.wkq.location.address.LocationConfig
import com.wkq.location.address.LocationResult
import com.wkq.location.address.LocationType
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** 使用虚拟系统定位服务验证任务生命周期，不使用设备、网络或真实坐标。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationKitTest {
    /** 每次测试建立授权和虚拟 GPS Provider。 */
    @Before fun prepare() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        LocationKit.init(app, LocationConfig().setLocationType(LocationType.SINGLE).setTimeout(50L))
    }

    /** 单例清理所有任务，避免跨测试保留回调。 */
    @After fun cleanup() { LocationKit.stopLocation() }

    /** 无真实位置的超时必须报告失败，不能将默认坐标包装成成功。 */
    @Test fun timeoutDoesNotGrantDefaultLocationSuccess() {
        val results = mutableListOf<LocationResult>()
        LocationKit.startLocation { results += it }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(1, results.size)
        assertFalse(results.single().success)
        assertNull(LocationKit.callback)
    }

    /** 手动停止同时终止超时任务，并丢弃之后到达的位置事件。 */
    @Test fun stopDiscardsTimeoutAndLateLocations() {
        var callbacks = 0
        LocationKit.startLocation { callbacks++ }
        LocationKit.stopLocation()
        LocationKit.processLocation(Location("gps"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(0, callbacks)
        assertNull(LocationKit.callback)
    }

    /** 单次成功只发送一次，不残留持有页面的回调。 */
    @Test fun singleRequestReleasesCallbackAfterFirstFix() {
        var callbacks = 0
        LocationKit.startLocation { assertTrue(it.success); callbacks++ }
        val location = Location("gps").apply { latitude = 1.0; longitude = 2.0; accuracy = 3f }
        LocationKit.processLocation(location)
        LocationKit.processLocation(location)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, callbacks)
        assertNull(LocationKit.callback)
    }

    /** 新请求替换旧请求时，旧超时不能影响新会话。 */
    @Test fun newRequestCancelsOldSession() {
        var oldCallbacks = 0
        var newCallbacks = 0
        LocationKit.startLocation { oldCallbacks++ }
        LocationKit.startLocation { newCallbacks++ }
        LocationKit.processLocation(Location("gps"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(0, oldCallbacks)
        assertEquals(1, newCallbacks)
    }

    /** 撤回全部权限后启动必须失败，不注册监听或保留回调。 */
    @Test fun missingPermissionsFailWithoutStarting() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        var result: LocationResult? = null
        LocationKit.startLocation { result = it }
        assertEquals(false, result?.success)
        assertNull(LocationKit.callback)
    }

    /** 已排队位置在临时授权撤回后不得作为成功结果交付。 */
    @Test fun revokedPermissionDiscardsQueuedLocation() {
        val results = mutableListOf<LocationResult>()
        LocationKit.startLocation { results += it }
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        LocationKit.processLocation(Location("gps"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, results.size)
        assertFalse(results.single().success)
        assertNull(results.single().location)
        assertNull(LocationKit.callback)
    }
}
