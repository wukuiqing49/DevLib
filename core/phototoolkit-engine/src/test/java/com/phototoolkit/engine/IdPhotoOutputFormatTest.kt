package com.phototoolkit.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class IdPhotoOutputFormatTest {

    @Test
    fun usesStableFileExtensionsForExportAndDiagnostics() {
        assertEquals("jpg", IdPhotoOutputFormat.JPEG.fileExtension)
        assertEquals("png", IdPhotoOutputFormat.PNG.fileExtension)
        assertEquals(true, IdPhotoOutputFormat.JPEG.acceptsExtension("jpeg"))
        assertEquals(false, IdPhotoOutputFormat.PNG.acceptsExtension("jpg"))
    }
}
