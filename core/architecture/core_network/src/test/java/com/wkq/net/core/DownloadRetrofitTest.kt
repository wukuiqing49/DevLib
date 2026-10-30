package com.wkq.net.core

import java.io.File
import java.io.IOException
import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRetrofitTest {

    @Test
    fun downloadReplacesDestinationOnlyAfterSuccess() = runBlocking {
        withDestinationFile { destination ->
            destination.writeText("old content")

            val states = "new content".toResponseBody().downloadFileFlow(destination).toList()

            assertEquals("new content", destination.readText())
            assertEquals(DownloadState.Success(destination), states.last())
        }
    }

    @Test
    fun failedDownloadKeepsExistingDestination() = runBlocking {
        withDestinationFile { destination ->
            destination.writeText("old content")

            val states = FailingResponseBody().downloadFileFlow(destination).toList()

            assertEquals("old content", destination.readText())
            check(states.last() is DownloadState.Error)
        }
    }

    private suspend fun withDestinationFile(block: suspend (File) -> Unit) {
        val directory = File(System.getProperty("java.io.tmpdir"), "core-network-${System.nanoTime()}")
        check(directory.mkdirs())
        try {
            block(File(directory, "download.txt"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private class FailingResponseBody : ResponseBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = FailingSource().buffer()
    }

    private class FailingSource : Source {
        private var firstRead = true

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (firstRead) {
                firstRead = false
                sink.writeUtf8("partial")
                return 7L
            }
            throw IOException("Connection interrupted")
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }
}
