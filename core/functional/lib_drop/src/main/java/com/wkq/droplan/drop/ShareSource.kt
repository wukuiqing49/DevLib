package com.wkq.droplan.drop

import java.io.InputStream

/** 本次授权的只读文件源；实现负责 SAF 授权，调用方负责关闭流。 */
interface ShareSource {
    val id: String
    val displayName: String
    val mimeType: String
    /** 未知长度用 null；禁止降为 Int 或将整文件载入内存。 */
    val length: Long?
    val supportsRandomAccess: Boolean
    /** 仅由可证明内容稳定的实现提供（如固定快照的摘要），不得用大小/mtime 冒充。 */
    val contentVersion: String? get() = null
    /** 仅来自发送端 SAF 树遍历，用于 ZIP 保留目录；不当作磁盘路径。 */
    val archivePath: String? get() = null
    fun openStream(): InputStream
    /** 不支持随机访问时必须失败，不允许伪造 Range 能力。 */
    fun openRange(offset: Long, length: Long): InputStream
}

/** HTTP 层使用的单区间解析结果；多区间请求允许忽略 Range 后返回完整内容。 */
sealed interface RangeResult {
    data object Ignore : RangeResult
    data object Unsatisfiable : RangeResult
    data class Partial(val first: Long, val last: Long) : RangeResult {
        val length: Long get() = last - first + 1
    }
}

object HttpByteRange {
    fun parse(header: String?, size: Long): RangeResult {
        require(size >= 0)
        if (header == null || !header.startsWith("bytes=")) return RangeResult.Ignore
        val value = header.removePrefix("bytes=").trim()
        if (',' in value) return RangeResult.Ignore
        val parts = value.split('-')
        if (parts.size != 2 || parts.all { it.isEmpty() }) return RangeResult.Ignore
        if (parts.any { part -> part.any { it !in '0'..'9' } }) return RangeResult.Ignore
        val start = parts[0].takeIf { it.isNotEmpty() }?.toLongOrNull()
        val end = parts[1].takeIf { it.isNotEmpty() }?.toLongOrNull()
        if ((parts[0].isNotEmpty() && start == null) ||
            (parts[1].isNotEmpty() && end == null)) return RangeResult.Ignore
        if (size == 0L) return RangeResult.Unsatisfiable
        if (start == null) {
            if (end == 0L) return RangeResult.Unsatisfiable
            return RangeResult.Partial(size - minOf(end!!, size), size - 1)
        }
        if (end != null && end < start) return RangeResult.Ignore
        if (start >= size) return RangeResult.Unsatisfiable
        return RangeResult.Partial(start, minOf(end ?: (size - 1), size - 1))
    }
}
