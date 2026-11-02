package com.wkq.location.sensor

/**
 *
 *@Author: wkq
 *
 *@Time: 2025/9/17 9:28
 *
 *@Desc: 一维卡尔曼滤波器，用于经纬度平滑
 */


class KalmanFilter(
    var processNoise: Double = 1e-5,
    var measurementNoise: Double = 1e-2
) {
    private var lat = 0.0
    private var lng = 0.0
    private var pLat = 1.0
    private var pLng = 1.0
    private var initialized = false

    /** 以经纬度测量更新卡尔曼估计，首次输入直接作为初始状态。 */
    fun update(latMeasurement: Double, lngMeasurement: Double): Pair<Double, Double> {
        if (!initialized) {
            lat = latMeasurement
            lng = lngMeasurement
            initialized = true
            return Pair(lat, lng)
        }

        // 纬度
        pLat += processNoise
        val kLat = pLat / (pLat + measurementNoise)
        lat += kLat * (latMeasurement - lat)
        pLat *= (1 - kLat)

        // 经度
        pLng += processNoise
        val kLng = pLng / (pLng + measurementNoise)
        lng += kLng * (lngMeasurement - lng)
        pLng *= (1 - kLng)

        return Pair(lat, lng)
    }

    /** 清空当前推算或滤波会话状态，使下一次采样重新建立起点。 */
    fun reset() {
        initialized = false
        lat = 0.0
        lng = 0.0
        pLat = 1.0
        pLng = 1.0
    }
}
