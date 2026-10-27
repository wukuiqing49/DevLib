package com.wkq.dev.demo.common

import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 使用真实临时文件验证素材解析的整批拒绝与顺序保留。 */
class WorkspaceSelectionTest {
    @get:Rule val folder = TemporaryFolder()

    /** 创建工作区资产夹具，文件实际存在，便于复现选中后被删除的情形。 */
    private fun asset(id: String, extension: String = "jpg") =
        WorkspaceAsset(id, folder.newFile("$id.$extension").path, id, "input", "", 0, 0)

    /** 全部素材有效时保留请求顺序，不改成数据库或文件名排序。 */
    @Test fun validBatchKeepsRequestedOrder() {
        val a = asset("a")
        val b = asset("b")
        val files = mapOf("a" to a, "b" to b)
        assertEquals(listOf(b, a), resolveWorkspaceAssets(listOf("b", "a"), "/document/core_pdf", files::get))
    }

    /** 部分记录丢失时整体失败，不返回可用文件的子集。 */
    @Test fun missingAssetRejectsWholeBatchAndCanRetry() {
        val a = asset("a")
        val files = mutableMapOf("a" to a)
        try {
            resolveWorkspaceAssets(listOf("a", "b"), "/document/core_pdf", files::get)
            fail("Partial selection accepted")
        } catch (_: FileNotFoundException) {
            files["b"] = asset("b")
        }
        assertEquals(2, resolveWorkspaceAssets(listOf("a", "b"), "/document/core_pdf", files::get).size)
    }

    /** 数据库记录存在但磁盘文件已被删除时仍拒绝接收。 */
    @Test(expected = FileNotFoundException::class)
    fun removedFileRejectsBatch() {
        val a = asset("a")
        assertTrue(a.file.delete())
        resolveWorkspaceAssets(listOf("a"), "/files/core_nas") { a }
    }

    /** 图片工具拒绝混入文档的整个批次，不能只过滤出图片。 */
    @Test(expected = IllegalArgumentException::class)
    fun incompatibleAssetRejectsBatch() {
        val files = mapOf("a" to asset("a"), "b" to asset("b", "pdf"))
        resolveWorkspaceAssets(listOf("a", "b"), "/document/core_pdf", files::get)
    }

    /** 解析前拒绝重复、空集合和超量输入，不执行存储查询。 */
    @Test fun invalidSizeOrDuplicatesDoNotReadStore() {
        for (ids in listOf(emptyList(), listOf("a", "a"), (1..21).map { it.toString() })) {
            try {
                resolveWorkspaceAssets(ids, "/files/core_nas") { fail("Unexpected lookup"); null }
                fail("Invalid batch accepted")
            } catch (_: IllegalArgumentException) {
                // 已在读取前拒绝非法批次。
            }
        }
    }
}
