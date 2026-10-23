package com.wkq.iptc.feature.press.metadata

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MetadataProcessRunnerTest {
    @get:Rule val folder = TemporaryFolder()

    private fun command(mode: String): ProcessBuilder {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classes = File(MetadataProcessFixture::class.java.protectionDomain!!.codeSource.location.toURI()).path
        return ProcessBuilder(java, "-Xms8m", "-Xmx32m", "-XX:+UseSerialGC", "-cp", classes,
            MetadataProcessFixture::class.java.name, mode)
    }

    @Test fun readsSuccessfulOutputAndRemovesTemporaryFiles() = runBlocking {
        assertEquals("ok", MetadataProcessRunner.run(command("ok"), folder.root).toString(Charsets.UTF_8))
        assertTrue(folder.root.listFiles()!!.joinToString { it.name }, folder.root.listFiles()!!.isEmpty())
    }

    @Test fun rejectsWarningsWithoutExposingMetadata() = runBlocking {
        val failure = runCatching { MetadataProcessRunner.run(command("warning"), folder.root) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(failure!!.message.orEmpty().contains("private"))
        assertTrue(failure.message.orEmpty().contains("exit=0"))
        assertTrue(failure.message.orEmpty().contains("stderrBytes="))
        assertTrue(folder.root.listFiles()!!.joinToString { it.name }, folder.root.listFiles()!!.isEmpty())
    }

    @Test fun stopsHangingProcessWithinDeadline() = runBlocking {
        val started = System.nanoTime()
        val failure = runCatching {
            MetadataProcessRunner.run(command("hang"), folder.root, timeoutMillis = 500,
                terminate = { it.destroyForcibly() })
        }.exceptionOrNull()
        assertEquals("Metadata operation timed out", failure?.message)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertTrue(folder.root.listFiles()!!.joinToString { it.name }, folder.root.listFiles()!!.isEmpty())
    }

    @Test fun readWarningsRequireExplicitPolicyAndSuccessfulExit() = runBlocking {
        assertEquals("ok", MetadataProcessRunner.run(command("readWarning"), folder.root,
            allowReadWarnings = true).toString(Charsets.UTF_8))
        for ((mode, allow) in listOf("readWarning" to false, "failedReadWarning" to true, "warning" to true)) {
            val failure = runCatching { MetadataProcessRunner.run(command(mode), folder.root, allowReadWarnings = allow) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertFalse(failure!!.message.orEmpty().contains("private"))
        }
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun cancellationDoesNotWaitForBlockedOutput() = runBlocking {
        val started = System.nanoTime()
        val job = launch(Dispatchers.IO) {
            MetadataProcessRunner.run(command("hang"), folder.root, terminate = { it.destroyForcibly() })
        }
        delay(250)
        job.cancelAndJoin()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertTrue(folder.root.listFiles()!!.joinToString { it.name }, folder.root.listFiles()!!.isEmpty())
    }

    @Test fun rejectsOversizeOutput() = runBlocking {
        val failure = runCatching {
            MetadataProcessRunner.run(command("overflow"), folder.root, outputLimit = 1024)
        }.exceptionOrNull()
        assertEquals("Metadata output exceeds limit", failure?.message)
        assertTrue(folder.root.listFiles()!!.joinToString { it.name }, folder.root.listFiles()!!.isEmpty())
    }
}
