package com.wkq.droplan.drop

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpByteRangeTest {
    @Test fun largeFileAndSuffix() {
        val size = 8L * 1024 * 1024 * 1024
        assertEquals(RangeResult.Partial(4294967296L, size - 1), HttpByteRange.parse("bytes=4294967296-", size))
        assertEquals(RangeResult.Partial(size - 10, size - 1), HttpByteRange.parse("bytes=-10", size))
        assertEquals(RangeResult.Partial(0, size - 1), HttpByteRange.parse("bytes=-9223372036854775807", size))
    }
    @Test fun invalidAndUnsatisfiable() {
        assertEquals(RangeResult.Unsatisfiable, HttpByteRange.parse("bytes=10-", 10))
        assertEquals(RangeResult.Unsatisfiable, HttpByteRange.parse("bytes=-0", 10))
        assertEquals(RangeResult.Unsatisfiable, HttpByteRange.parse("bytes=0-", 0))
        for (header in listOf("bytes=5-2", "bytes=0-1,3-4", "bytes=x-", "bytes=9223372036854775808-", "items=0-1")) {
            assertEquals(header, RangeResult.Ignore, HttpByteRange.parse(header, 10))
        }
    }
}
