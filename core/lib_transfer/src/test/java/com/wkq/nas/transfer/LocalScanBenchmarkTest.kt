package com.wkq.nas.transfer

import com.wkq.nas.transfer.provider.LocalProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 本机真实目录枚举基准，只表示元数据扫描，不代替内容哈希或NAS吞吐。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LocalScanBenchmarkTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun measureFullAndSelectedDirectoryScan() = runBlocking {
        val root = temporary.newFolder("scan")
        for (directory in 0 until 16) {
            val folder = File(root,"folder-$directory").apply { mkdirs() }
            repeat(16) { File(folder,"file-$it.txt").writeBytes(ByteArray(4096) { it.toByte() }) }
        }
        val provider = LocalProvider(root.path)
        val samples = listOf(emptySet<String>() to 272,setOf("folder-0") to 17).map { (selected,expected) ->
            assertEquals(expected,scanSyncScope(provider,"",selected).size)
            val durations = (0 until 7).map {
                val start = System.nanoTime(); val files = scanSyncScope(provider,"",selected)
                val elapsed = System.nanoTime()-start; assertEquals(expected,files.size); elapsed
            }
            "{\"selectedDirectory\":${selected.isNotEmpty()},\"entries\":$expected,\"samplesNs\":[${durations.joinToString(",")}],\"medianNs\":${durations.sorted()[3]}}"
        }
        File("build/local-scan-benchmark.json").apply { parentFile?.mkdirs(); writeText("[${samples.joinToString(",")}]\n") }; Unit
    }
}
