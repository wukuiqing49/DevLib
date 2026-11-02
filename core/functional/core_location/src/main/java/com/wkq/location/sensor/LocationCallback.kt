package com.wkq.location.sensor

/**
 *
 *@Author: wkq
 *
 *@Time: 2025/9/17 9:29
 *
 *@Desc:
 */
/** 接收融合位置与设备传感器支持情况，由宿主管理监听生命周期。 */
interface LocationCallback {
    // 经度 纬度 海拔高度, 航向(角度)
    /** 将融合后的坐标、高度、航向和速度交给当前监听方。 */
    fun onLocationUpdate(info :SensorPointInfo)

    /**
     * 设备传感器支持状态回调
     * @param sensors 每个传感器类型和是否支持
     */
    fun onSensorSupportStatus(sensors: List<SensorStatus>)
}