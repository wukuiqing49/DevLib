package com.wkq.nativepdf

import com.wkq.nativepdf.render.pdfImageSampleSize
import org.junit.Assert.*
import org.junit.Test

class PdfImageSamplingTest {
    @Test fun normalPhotosKeepExistingResolution() {
        assertEquals(2, pdfImageSampleSize(4000, 3000, 1000, 1000))
        assertEquals(1, pdfImageSampleSize(800, 600, 1000, 1000))
    }

    @Test fun panoramicAndExtremeDimensionsStayWithinMemoryBudget() {
        for ((width, height) in listOf(60000 to 1000, 1000 to 60000, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val sample = pdfImageSampleSize(width, height, 1000, 1000)
            assertTrue(sample > 0 && sample and (sample - 1) == 0)
            val decodedWidth = (width.toLong() + sample - 1) / sample
            val decodedHeight = (height.toLong() + sample - 1) / sample
            assertTrue(decodedWidth * decodedHeight <= 4L * 1024 * 1024)
            assertTrue(decodedWidth <= 8192 && decodedHeight <= 8192)
        }
    }
}
