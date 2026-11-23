package com.wkq.logreport

/** 原生栈只保留离线符号化所需模块、相对地址和ELF Build ID。 */
internal data class NativeFrame(val module: String, val relativePc: Long, val buildId: String, val function: String, val functionOffset: Long = 0)
/** 由系统提供的原生崩溃白名单，不包含内存、寄存器、地址路径及日志正文。 */
internal data class NativeTrace(val architecture: Int, val signal: Int, val thread: Long, val frames: List<NativeFrame>) {
    /** 标准地址格式可以直接交给开发者离线定位。 */
    fun text(): String = "architecture=$architecture signal=$signal tid=$thread\n" + frames.mapIndexed { index, frame ->
        "#" + index.toString().padStart(2, '0') + " pc " + java.lang.Long.toHexString(frame.relativePc) + " " + frame.module + " BuildId=" + frame.buildId + " " + frame.function + "+0x" + java.lang.Long.toHexString(frame.functionOffset)
    }.joinToString("\n")
}

/** 按AOSP tombstone.proto读取必要字段，跳过所有未许可载荷；不落盘原始protobuf。 */
internal object NativeTombstone {
    /** 只保留白名单字段的数值或有界字节串。 */
    private data class Field(val number: Int, val numeric: Long = 0, val bytes: ByteArray? = null)

    /** 限制输入、字段数量和栈帧，损坏或不匹配的输入明确失败。 */
    fun parse(data: ByteArray): NativeTrace {
        require(data.size <= 2 * 1024 * 1024)
        val root = fields(data, setOf(1, 6, 10, 16))
        val threadId = root.firstOrNull { it.number == 6 }?.numeric ?: 0
        val signalFields = fields(checkNotNull(root.firstOrNull { it.number == 10 }?.bytes), setOf(1))
        val signal = signalFields.firstOrNull { it.number == 1 }?.numeric?.toInt() ?: 0
        require(signal in 1..64)
        val threads = root.filter { it.number == 16 }.take(256).map { fields(checkNotNull(it.bytes), setOf(1, 2)) }
        val selected = threads.firstOrNull { values -> values.firstOrNull { it.number == 1 }?.numeric == threadId }
        val threadBytes = selected?.firstOrNull { it.number == 2 }?.bytes
        val frames = if (threadBytes == null) emptyList() else fields(threadBytes, setOf(4)).take(128).map { field ->
            val values = fields(checkNotNull(field.bytes), setOf(1, 4, 5, 6, 8))
            val path = values.firstOrNull { it.number == 6 }?.bytes?.toString(Charsets.UTF_8).orEmpty()
            val module = path.substringAfterLast('/').removeSuffix(" (deleted)").take(160)
                .takeIf { it.matches(Regex("[A-Za-z0-9_.+-]+")) } ?: "unknown_module"
            val build = values.firstOrNull { it.number == 8 }?.bytes?.toString(Charsets.UTF_8).orEmpty()
                .takeIf { it.matches(Regex("[A-Fa-f0-9]{4,128}")) }.orEmpty().lowercase()
            NativeFrame(module, values.firstOrNull { it.number == 1 }?.numeric ?: 0, build,
                LogPrivacy.redact(values.firstOrNull { it.number == 4 }?.bytes?.toString(Charsets.UTF_8).orEmpty()), values.firstOrNull { it.number == 5 }?.numeric ?: 0)
        }
        return NativeTrace(root.firstOrNull { it.number == 1 }?.numeric?.toInt() ?: 0, signal, threadId, frames)
    }

    /** 只复制许可字段，未知长度字段直接跳过，避免保存内存和日志内容。 */
    private fun fields(data: ByteArray, allowed: Set<Int>): List<Field> {
        val result = mutableListOf<Field>()
        var offset = 0
        var count = 0
        /** 读取protobuf的有界varint。 */
        fun number(): Long {
            var value = 0L
            for (shift in 0..9) {
                require(offset < data.size)
                val byte = data[offset++].toInt() and 255
                if (shift == 9) require(byte <= 1)
                value = value or ((byte and 127).toLong() shl (shift * 7))
                if (byte and 128 == 0) return value
            }
            error("Malformed varint")
        }
        while (offset < data.size) {
            require(++count <= 100000)
            val tag = number()
            val id = (tag ushr 3).toInt()
            require(id > 0)
            when ((tag and 7).toInt()) {
                0 -> { val value = number(); if (id in allowed) result += Field(id, value) }
                1 -> { require(data.size - offset >= 8); offset += 8 }
                2 -> {
                    val length = number()
                    require(length in 0..(data.size - offset).toLong())
                    val end = offset + length.toInt()
                    if (id in allowed) result += Field(id, bytes = data.copyOfRange(offset, end))
                    offset = end
                }
                5 -> { require(data.size - offset >= 4); offset += 4 }
                else -> error("Unsupported wire type")
            }
        }
        return result
    }
}
