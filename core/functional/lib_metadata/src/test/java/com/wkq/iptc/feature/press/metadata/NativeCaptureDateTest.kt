package com.wkq.iptc.feature.press.metadata

import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class NativeCaptureDateTest {
    @Test fun preservesValidCameraDateIncludingLeapDay() {
        assertEquals("2024:02:29 23:59:59", NativeCaptureDate.preserveOrNow("2024:02:29 23:59:59"))
    }

    @Test fun rejectsInvalidCalendarDatesAndTrailingText() {
        listOf("2023:02:29 12:00:00", "2024:13:01 00:00:00", "2024:01:01 24:00:00",
            "0000:01:01 00:00:00", "2024:01:01 00:00:00junk", "2024:1:1 0:0:0", "").forEach {
            assertNull(it, NativeCaptureDate.parse(it))
        }
    }

    @Test fun invalidDateFallsBackToKnownTime() {
        val expected = NativeCaptureDate.preserveOrNow("", Date(0))
        assertEquals(expected, NativeCaptureDate.preserveOrNow("2023:02:29 12:00:00", Date(0)))
        assertNotNull(NativeCaptureDate.parse(expected))
    }
}
