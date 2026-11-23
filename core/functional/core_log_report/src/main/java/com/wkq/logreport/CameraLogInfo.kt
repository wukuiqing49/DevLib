package com.wkq.logreport

/** 宿主提供的相机环境白名单，不包含会话地址、序列号或用户照片信息。 */
data class CameraLogInfo(val model: String, val firmware: String, val protocol: String)
