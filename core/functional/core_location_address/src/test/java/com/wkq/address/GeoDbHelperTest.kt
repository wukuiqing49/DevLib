package com.wkq.address

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 从真实迁入的数据库验证复制、查询与重复打开，不连接外部服务。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeoDbHelperTest {
    /** 使用数据库真实记录的坐标查询自身，验证资源与表结构匹配。 */
    @Test fun bundledDatabaseCanBeQueriedAfterReopening() {
        val app = RuntimeEnvironment.getApplication()
        val first = GeoDbHelper(app).use { helper ->
            val database = helper.readableDatabase
            database.rawQuery("SELECT lat, lon FROM geonames LIMIT 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val lat = cursor.getDouble(0)
                val lng = cursor.getDouble(1)
                val found = helper.getNearestPlaceResult(lat, lng)
                assertNotNull(found)
                assertEquals(0.0, found!!.distanceKm, 0.001)
                lat to lng
            }
        }
        GeoDbHelper(app).use { helper ->
            assertNotNull(helper.getNearestPlaceResult(first.first, first.second))
        }
    }

    /** 无对应覆盖的坐标返回空结果，不合成默认地址。 */
    @Test fun uncoveredCoordinateHasNoFabricatedAddress() {
        GeoDbHelper(RuntimeEnvironment.getApplication()).use { helper ->
            assertNull(helper.getNearestPlaceResult(0.0, 0.0, radius = 0.0000001))
        }
    }
}
