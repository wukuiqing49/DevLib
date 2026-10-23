package com.wkq.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.Locale

/**
 * 设备信息工具。
 *
 * Android ID 会随设备用户、签名和系统策略变化，不适合作为强一致账号标识。
 */
object PhoneUtil {

    /** 品牌，例如 google、samsung。 */
    fun getBrand(): String = Build.BRAND.orEmpty()

    /** 制造商，例如 Google、Xiaomi。 */
    fun getManufacturer(): String = Build.MANUFACTURER.orEmpty()

    /** 机型名称。 */
    fun getModel(): String = Build.MODEL.orEmpty()

    /** 设备代号。 */
    fun getDevice(): String = Build.DEVICE.orEmpty()

    /** 产品代号。 */
    fun getProduct(): String = Build.PRODUCT.orEmpty()

    /** 硬件平台。 */
    fun getHardware(): String = Build.HARDWARE.orEmpty()

    /** Android 版本名，例如 14。 */
    fun getAndroidVersion(): String = Build.VERSION.RELEASE.orEmpty()

    /** Android API Level。 */
    fun getSdkInt(): Int = Build.VERSION.SDK_INT

    /** 获取 ANDROID_ID。 */
    fun getAndroidId(context: Context): String {
        return Settings.Secure.getString(
            context.applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
    }

    /** 基于常见字段粗略判断是否运行在模拟器。 */
    fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.US)
        val model = Build.MODEL.lowercase(Locale.US)
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.US)
        val brand = Build.BRAND.lowercase(Locale.US)
        val device = Build.DEVICE.lowercase(Locale.US)
        val product = Build.PRODUCT.lowercase(Locale.US)

        return fingerprint.startsWith("generic") ||
            fingerprint.contains("vbox") ||
            fingerprint.contains("test-keys") ||
            model.contains("google_sdk") ||
            model.contains("emulator") ||
            model.contains("android sdk built for") ||
            manufacturer.contains("genymotion") ||
            (brand.startsWith("generic") && device.startsWith("generic")) ||
            product.contains("sdk") ||
            product.contains("emulator")
    }

    /** 生成便于日志上报的设备摘要。 */
    fun getDeviceSummary(context: Context? = null): String {
        val androidId = context?.let { getAndroidId(it) }.orEmpty()
        return buildString {
            append(getManufacturer())
            append(' ')
            append(getModel())
            append(" / Android ")
            append(getAndroidVersion())
            append(" (API ")
            append(getSdkInt())
            append(')')
            if (androidId.isNotEmpty()) {
                append(" / ")
                append(androidId)
            }
        }
    }
}
