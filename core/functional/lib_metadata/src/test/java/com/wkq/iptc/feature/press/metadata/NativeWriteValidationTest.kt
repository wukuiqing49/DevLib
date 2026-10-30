package com.wkq.iptc.feature.press.metadata

import android.location.Location
import com.wkq.dev.metadata.model.MetadataTemplate
import com.wkq.dev.metadata.model.MetadataFieldDefinitions as F
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class NativeWriteValidationTest {
    @get:Rule val folder = TemporaryFolder()
    private val context get() = org.robolectric.RuntimeEnvironment.getApplication()

    @Test fun invalidGpsFailsBeforeRuntimeAndPreservesSource() = runBlocking {
        val file = folder.newFile("camera.heic").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val before = file.readBytes()
        for (latitude in listOf(Double.NaN, Double.POSITIVE_INFINITY, 91.0)) {
            val location = Location("test").apply { this.latitude = latitude; longitude = 1.0 }
            val failure = runCatching {
                NativeContainerMetadata.write(context, file, MetadataTemplate(id = "invalid-gps"), location)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun unsupportedIimFailsBeforeRuntimeAndPreservesSource() = runBlocking {
        val file = folder.newFile("camera.nef").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val template = MetadataTemplate(id = "unsupported-iim", enabledFields = listOf(F.IIM_OBJECT_TYPE),
            standardFields = mapOf(F.IIM_OBJECT_TYPE to "unsupported"))
        val failure = runCatching { NativeContainerMetadata.write(context, file, template, null) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertArrayEquals(byteArrayOf(4, 5, 6), file.readBytes())
    }
}
