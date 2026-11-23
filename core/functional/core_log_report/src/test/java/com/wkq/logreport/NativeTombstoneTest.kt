package com.wkq.logreport

import org.junit.Assert.*
import org.junit.Test

/** 验证AOSP原生栈字段读取、隐私字段丢弃及损坏输入边界。 */
class NativeTombstoneTest {
    /** 编码测试用有界varint。 */
    private fun number(value: Long): ByteArray {
        var left = value
        val result = java.io.ByteArrayOutputStream()
        do { var byte = (left and 127).toInt(); left = left ushr 7; if (left != 0L) byte = byte or 128; result.write(byte) } while (left != 0L)
        return result.toByteArray()
    }
    /** 构造整数类型测试字段。 */
    private fun integer(id: Int, value: Long) = number((id * 8).toLong()) + number(value)
    /** 构造带长度的测试字段。 */
    private fun bytes(id: Int, value: ByteArray) = number((id * 8 + 2).toLong()) + number(value.size.toLong()) + value

    /** 按真实字段编号提取必要栈信息并丢弃隐私载荷。 */
    @Test fun extractsOnlyBuildIdRelativePcAndModule() {
        val frame = integer(1, 0x1234) + integer(5, 24) + bytes(6, "/data/private/libcamera.so".toByteArray()) + bytes(8, "abc12345".toByteArray())
        val thread = integer(1, 42) + bytes(2, "private-thread".toByteArray()) + bytes(4, frame) + bytes(5, "secret-memory".toByteArray())
        val entry = integer(1, 42) + bytes(2, thread)
        val root = integer(1, 1) + integer(6, 42) + bytes(10, integer(1, 11)) + bytes(14, "private-abort".toByteArray()) + bytes(16, entry)
        val trace = NativeTombstone.parse(root)
        assertEquals(11, trace.signal)
        assertEquals(0x1234L, trace.frames.single().relativePc)
        assertEquals(24L, trace.frames.single().functionOffset)
        assertEquals("abc12345", trace.frames.single().buildId)
        assertEquals("libcamera.so", trace.frames.single().module)
        listOf("private", "secret-memory").forEach { assertFalse(trace.text().contains(it)) }
    }
    /** 损坏和超限输入明确失败，不继续错误解析。 */
    @Test fun rejectsMalformedAndOversizedInput() {
        assertTrue(runCatching { NativeTombstone.parse(byteArrayOf(0x82.toByte(), 0xff.toByte())) }.isFailure)
        assertTrue(runCatching { NativeTombstone.parse(ByteArray(2 * 1024 * 1024 + 1)) }.isFailure)
    }
}
