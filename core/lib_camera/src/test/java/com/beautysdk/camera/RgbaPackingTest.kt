package com.beautysdk.camera

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class RgbaPackingTest {
    @Test fun copiesCropWithPositionRowPaddingAndPixelStride() {
        val bytes = ByteArray(1+3*24) { -1 }
        for (y in 0..2) for (x in 0..2) for (c in 0..3) bytes[1+y*24+x*6+c]=(y*30+x*4+c).toByte()
        val buffer = ByteBuffer.wrap(bytes).apply { position(1) }
        val result = RgbaPacking.copy(buffer,24,6,1,1,2,2)
        assertArrayEquals(byteArrayOf(34,35,36,37,38,39,40,41,64,65,66,67,68,69,70,71),result)
        assertEquals(1,buffer.position())
    }
    @Test fun copiesRgbaChannelsWithoutArgbReordering() {
        val result = RgbaPacking.copy(ByteBuffer.wrap(byteArrayOf(1,2,3,-1,4,5,6,127)),8,4,0,0,2,1)
        assertArrayEquals(byteArrayOf(1,2,3,-1,4,5,6,127),result)
    }
    @Test fun rejectsTruncatedPlane() {
        assertThrows(IllegalArgumentException::class.java) {
            RgbaPacking.copy(ByteBuffer.allocate(7),8,4,0,0,2,1)
        }
    }
    @Test fun rejectsCropOutsideStrideAndOverflowSizes() {
        assertThrows(IllegalArgumentException::class.java) {
            RgbaPacking.copy(ByteBuffer.allocate(16),8,4,1,0,2,1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RgbaPacking.copy(ByteBuffer.allocate(16),Int.MAX_VALUE,4,0,Int.MAX_VALUE,Int.MAX_VALUE,2)
        }
    }
    @Test fun rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { CameraConfig(maxAnalysisFps=0) }
        assertThrows(IllegalArgumentException::class.java) { CameraConfig(width=0) }
    }
}
