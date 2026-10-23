package com.wkq.nas.transfer

import org.junit.Assert.*
import org.junit.Test

class SyncPlannerTest {
    @Test fun planPreservesExtraTargetsAndSeparatesConflicts() {
        val source = listOf(FileMetadata("photos/new.jpg", false, 4, 2000), FileMetadata("photos/same.jpg", false, 8, 3000),
            FileMetadata("photos/changed.jpg", false, 10, 4000), FileMetadata("photos/empty", true, 0, 4000))
        val target = listOf(FileMetadata("backup/same.jpg", false, 8, 3001), FileMetadata("backup/changed.jpg", false, 9, 4000),
            FileMetadata("backup/extra.jpg", false, 1, 1000))
        val plan = SyncPlanner.plan(source, target, "photos", "backup")
        assertEquals(listOf(SyncAction.ADD, SyncAction.SKIP, SyncAction.CONFLICT, SyncAction.ADD), plan.map { it.action })
        assertFalse(plan.any { it.targetPath.endsWith("extra.jpg") })
    }
    @Test fun unknownTimestampsCannotProveEquality() {
        val source = listOf(FileMetadata("a", false, 10, -1))
        val target = listOf(FileMetadata("a", false, 10, -1))
        assertEquals(SyncAction.CONFLICT, SyncPlanner.plan(source, target, "", "").single().action)
    }
    @Test fun emptySourceDirectoryIsPlanned() {
        assertEquals("backup", SyncPlanner.plan(listOf(FileMetadata("", true, 0, -1)), emptyList(), "", "backup").single().targetPath)
    }
    @Test fun invalidRelativePathsAreRejected() {
        listOf("../a", "a/../b", "/absolute", "a\\b", "a\u0000b", "./a").forEach { path ->
            try { TransferPaths.normalize(path); fail(path) } catch (_: IllegalArgumentException) { }
        }
        assertEquals("a/b", TransferPaths.normalize("a//b/"))
    }
}
