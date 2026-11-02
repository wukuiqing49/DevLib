package com.wkq.location.address

import android.location.Location

/**
 *
 *@Author: wkq
 *
 *@Time: 2025/9/5 10:40
 *
 *@Desc:
 */
/** 定位结果契约；失败也可能携带默认位置，消费方必须检查 success 与来源。 */
data class LocationResult(
    val success: Boolean,
    val location: Location?,
    val msg: String
)
