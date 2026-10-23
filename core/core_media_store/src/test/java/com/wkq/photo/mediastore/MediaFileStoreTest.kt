package com.wkq.photo.mediastore

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.wkq.photo.core.operation.OperationBudget
import com.wkq.photo.core.operation.OperationErrorCode
import com.wkq.photo.core.operation.OperationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaFileStoreTest {

    private val context: android.content.Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun materializesFileUriAndParcelFileDescriptorSources() {
        val source = Files.createTempFile("media-source", ".txt").toFile().apply { writeText("PhotoKit") }

        val fileOutput = materialize(MediaInputSource.FileSource(source), "txt")
        val uriOutput = materialize(MediaInputSource.UriSource(Uri.fromFile(source)), "txt")
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val descriptorOutput = materialize(MediaInputSource.ParcelFileDescriptorSource(descriptor), "txt")
            assertEquals("PhotoKit", descriptorOutput.readText())
            assertTrue(descriptor.fileDescriptor.valid())
        }

        assertEquals("PhotoKit", fileOutput.readText())
        assertEquals("PhotoKit", uriOutput.readText())
    }

    @Test
    fun createsWorkingFilesInDedicatedCacheDirectory() {
        val workingFile = MediaFileStore.newWorkingFile(context, "task/preview", ".tiff")

        assertTrue(workingFile.isFile)
        assertEquals("media-work", workingFile.parentFile?.name)
        assertTrue(workingFile.name.startsWith("task_preview_"))
        assertEquals("tiff", workingFile.extension)
    }

    @Test
    fun rejectsUriMaterializationAfterInputByteBudget() {
        val source = Files.createTempFile("media-large-source", ".txt").toFile().apply { writeText("PhotoKit") }

        val result = MediaFileStore.materialize(
            context = context,
            source = MediaInputSource.UriSource(Uri.fromFile(source)),
            prefix = "budget",
            budget = OperationBudget(maxInputBytes = 4),
        )

        assertTrue(result is OperationResult.Failure)
        assertEquals(OperationErrorCode.BUDGET_EXCEEDED, (result as OperationResult.Failure).code)
    }

    @Test
    fun exactCopyRejectsTruncatedInput() {
        val output = ByteArrayOutputStream()

        val error = org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            copyExactly(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, expectedBytes = 4)
        }

        assertTrue(error.message.orEmpty().contains("expected 4"))
    }

    private fun materialize(source: MediaInputSource, extension: String): java.io.File {
        val result = MediaFileStore.materialize(context, source, "test", extension)
        assertTrue(result.toString(), result is OperationResult.Success)
        return (result as OperationResult.Success).value
    }
}
