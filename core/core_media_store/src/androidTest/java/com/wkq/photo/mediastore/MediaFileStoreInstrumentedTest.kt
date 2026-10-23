package com.wkq.photo.mediastore

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.exifinterface.media.ExifInterface
import com.wkq.photo.core.operation.OperationResult
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MediaFileStoreInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sourceUri = Uri.parse("content://${RegressionContentProvider.AUTHORITY}/image")

    @Before
    fun setUp() {
        RegressionContentProvider.denyRead = false
        val bitmap = Bitmap.createBitmap(1_280, 960, Bitmap.Config.ARGB_8888)
        try {
            File(context.cacheDir, RegressionContentProvider.SOURCE_FILE).outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @After
    fun tearDown() {
        RegressionContentProvider.denyRead = false
        File(context.cacheDir, RegressionContentProvider.SOURCE_FILE).delete()
    }

    @Test
    fun materializesImageFromContentUri() {
        val result = MediaFileStore.materialize(context, MediaInputSource.UriSource(sourceUri), "regression")
        assertTrue(result is OperationResult.Success)
        val file = (result as OperationResult.Success).value
        assertTrue(file.isFile && file.length() > 0L)
        file.delete()
    }

    @Test
    fun returnsFailureWhenContentUriPermissionIsLost() {
        RegressionContentProvider.denyRead = true
        val result = MediaFileStore.materialize(context, MediaInputSource.UriSource(sourceUri), "regression")
        assertTrue(result is OperationResult.Failure)
    }

    @Test
    fun preservesExifWhenMaterializingFileInput() {
        val source = File(context.cacheDir, "regression-exif.jpg")
        writeBitmap(source, Bitmap.CompressFormat.JPEG, 640, 480)
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_MAKE, "RegressionCamera")
            saveAttributes()
        }
        val result = MediaFileStore.materialize(context, MediaInputSource.FileSource(source), "regression")
        assertTrue(result is OperationResult.Success)
        val copied = (result as OperationResult.Success).value
        try {
            assertTrue(ExifInterface(copied).getAttribute(ExifInterface.TAG_MAKE) == "RegressionCamera")
        } finally {
            source.delete()
            copied.delete()
        }
    }

    @Test
    fun verifiesWebpOutputOnDevice() {
        val output = File(context.cacheDir, "regression.webp")
        writeBitmap(output, Bitmap.CompressFormat.WEBP_LOSSY, 640, 480)
        try {
            MediaOutputVerifier.verifySource(output, "image/webp")
        } finally {
            output.delete()
        }
    }

    private fun writeBitmap(file: File, format: Bitmap.CompressFormat, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { output -> check(bitmap.compress(format, 92, output)) }
        } finally {
            bitmap.recycle()
        }
    }
}
