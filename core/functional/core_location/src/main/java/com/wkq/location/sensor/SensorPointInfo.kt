package com.wkq.location.sensor

/**
 *
 *@Author: wkq
 *
 *@Time: 2025/9/17 9:29
 *
 *@Desc:
 */
/** 记录轨迹点的经纬度、高度、航向和速度，保留原公开数据结构。 */
data class SensorPointInfo(val lat: Double, val lng: Double, val altitude: Float, val heading: Float,val speed: Float=0f)