package com.wkq.location.address

/**
 *
 *@Author: wkq
 *
 *@Time: 2025/9/2 13:52
 *
 *@Desc:
 */
/** 定义宿主包内位置广播的动作与位置参数键，不隐式向其他应用发送。 */
object LocationBroadcast {
    const val ACTION_LOCATION_UPDATE = "com.wkq.location.LOCATION_UPDATE"
    const val EXTRA_LOCATION = "extra_location"
}