package com.wkq.iptc.feature.press.metadata

/** 仅由完成图片完整性检查的交付流程写入；不把可选字段警告冒充完整验证通过。 */
object MetadataDeliveryStatus {
    private const val PREFIX = "OPTIONAL_METADATA_SKIPPED:"
    fun warning(skipped: Collection<String>): String? = skipped.takeIf { it.isNotEmpty() }
        ?.let { PREFIX + it.distinct().joinToString(",") { key ->
            key.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) } ?: "optional"
        } }
    fun isPartialSuccess(message: String?): Boolean = message?.startsWith(PREFIX) == true &&
        message.removePrefix(PREFIX).matches(Regex("[A-Za-z0-9_.-]{1,100}(,[A-Za-z0-9_.-]{1,100})*"))
    fun withWarning(message: String?, key: String): String = warning(
        (if (isPartialSuccess(message)) message!!.removePrefix(PREFIX).split(',') else emptyList()) + key
    )!!
}

/** 完整性错误不得进入可选字段重试或降级。 */
class MetadataIntegrityException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
