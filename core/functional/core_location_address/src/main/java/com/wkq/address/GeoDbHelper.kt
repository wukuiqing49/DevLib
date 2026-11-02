package com.wkq.address

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.AtomicFile
import kotlin.io.copyTo
import kotlin.io.use
import kotlin.math.*

/**
 * @Author: wkq
 * @Time: 2025/8/29
 * @Desc: 根据经纬度查询地名的数据库帮助类（Kotlin 层计算距离，兼容 Android SQLite）
 */
class GeoDbHelper(private val context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {
    /**
     * 查询结果对象
     */
    data class GeoResult(
        val name: String,
        val admin1: String?,
        val admin2: String?,
        val lat: Double,
        val lon: Double,
        val distanceKm: Double
    ) {
        /** 格式化显示，例如：村镇 区 市 */
        fun toDisplayString(): String = buildString {
            append(name)
            if (!admin2.isNullOrEmpty()) append(" $admin2")
            if (!admin1.isNullOrEmpty()) append(" $admin1")
        }
    }

    /** 共享首次复制锁，保证并发创建帮助类时不会读取半份数据库。 */
    companion object {
        private val databaseCopyLock = Any()
        private const val DB_NAME = "location.db"
        private const val DB_VERSION = 1
        private const val EARTH_RADIUS_KM = 6371
    }

    init {
        copyDatabaseIfNeeded()
    }

    /** 在调用线程原子复制离线数据库；宿主应在 IO 线程构造帮助类。 */
    private fun copyDatabaseIfNeeded() = synchronized(databaseCopyLock) {
        val dbFile = context.applicationContext.getDatabasePath(DB_NAME)
        if (dbFile.exists()) return@synchronized
        dbFile.parentFile?.mkdirs()
        val atomic = AtomicFile(dbFile)
        val output = atomic.startWrite()
        try {
            context.applicationContext.assets.open(DB_NAME).use { it.copyTo(output) }
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }

    /** 预置数据库由资源复制提供，此处不创建另一套表结构。 */
    override fun onCreate(db: SQLiteDatabase) {}
    /** 当前仅维护版本一的预置数据，未来升级须提供显式迁移策略。 */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // --- 单个地点 ---

    /** 返回 String 格式（兼容旧接口） */
    fun getNearestPlace(lat: Double, lon: Double, radius: Double = 0.05): String? {
        return getNearestPlaceResult(lat, lon, radius)?.toDisplayString()
    }

    /** 返回完整 GeoResult 对象 */
    fun getNearestPlaceResult(lat: Double, lon: Double, radius: Double = 0.05): GeoResult? {
        return getNearbyPlacesInternal(lat, lon, radius, 1).firstOrNull()
    }

    /** 异步返回 GeoResult 对象 */
    suspend fun getNearestPlaceResultAsync(
        lat: Double, lon: Double, radius: Double = 0.05
    ): GeoResult? = withContext(Dispatchers.IO) {
        getNearestPlaceResult(lat, lon, radius)
    }

    // --- 多个地点 ---

    /** 返回 String 列表（兼容旧接口） */
    fun getNearbyPlaces(
        lat: Double, lon: Double, radius: Double = 0.05, limit: Int = 10
    ): List<String> {
        return getNearbyPlacesResult(lat, lon, radius, limit)
            .map { it.toDisplayString() }
    }

    /** 返回完整 GeoResult 列表 */
    fun getNearbyPlacesResult(
        lat: Double, lon: Double, radius: Double = 0.05, limit: Int = 10
    ): List<GeoResult> {
        return getNearbyPlacesInternal(lat, lon, radius, limit)
    }

    /** 异步返回完整 GeoResult 列表 */
    suspend fun getNearbyPlacesResultAsync(
        lat: Double, lon: Double, radius: Double = 0.05, limit: Int = 10
    ): List<GeoResult> = withContext(Dispatchers.IO) {
        getNearbyPlacesResult(lat, lon, radius, limit)
    }

    // --- 内部通用方法 ---

    /** 在经纬度窗口内查询数据库，按球面距离排序并限制返回数量。 */
    private fun getNearbyPlacesInternal(
        lat: Double, lon: Double, radius: Double, limit: Int
    ): List<GeoResult> {
        val db = readableDatabase
        val latMin = lat - radius
        val latMax = lat + radius
        val lonMin = lon - radius
        val lonMax = lon + radius

        val sql = """
            SELECT name_cn, admin1, admin2, lat, lon
            FROM geonames
            WHERE lat BETWEEN ? AND ?
              AND lon BETWEEN ? AND ?
        """.trimIndent()

        val cursor: Cursor = db.rawQuery(
            sql, arrayOf(latMin.toString(), latMax.toString(), lonMin.toString(), lonMax.toString())
        )

        val results = mutableListOf<GeoResult>()
        while (cursor.moveToNext()) {
            val name = cursor.getString(cursor.getColumnIndexOrThrow("name_cn"))
            val admin1 = cursor.getString(cursor.getColumnIndexOrThrow("admin1"))
            val admin2 = cursor.getString(cursor.getColumnIndexOrThrow("admin2"))
            val latRes = cursor.getDouble(cursor.getColumnIndexOrThrow("lat"))
            val lonRes = cursor.getDouble(cursor.getColumnIndexOrThrow("lon"))
            val distance = haversine(lat, lon, latRes, lonRes)
            results.add(GeoResult(name, admin1, admin2, latRes, lonRes, distance))
        }
        cursor.close()
        return results.sortedBy { it.distanceKm }.take(limit)
    }

    // --- Kotlin 层 Haversine 公式 ---

    /** 计算经纬度之间的球面距离，结果单位为千米。 */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }
}
