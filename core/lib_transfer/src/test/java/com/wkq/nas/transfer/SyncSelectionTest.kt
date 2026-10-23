package com.wkq.nas.transfer

import org.junit.Assert.*
import org.junit.Test

class SyncSelectionTest {
    @Test fun indexedSelectionMatchesExistingBoundarySemantics() {
        val paths = setOf("a", "a/x", "a-b/child", "目录/😀.txt")
        val selection = SyncSelection(paths)
        listOf("a", "a/x", "a/z/file", "a-b", "a-b/child", "a-b/other", "aa", "目录", "目录/😀.txt", "目录/other").forEach {
            assertEquals(it, AdvancedSyncPlanner.inScope(it, paths), selection.contains(it))
        }
    }
    @Test fun tenThousandSelectedFilesDoNotIncludeUnrelatedSiblings() {
        val selection = SyncSelection((0 until 10000).map { "folder-$it/file" }.toSet())
        assertTrue(selection.contains("folder-9999"))
        assertTrue(selection.contains("folder-9999/file"))
        assertFalse(selection.contains("folder-9999/file-extra"))
        assertFalse(selection.contains("folder-9999/other"))
    }
}
