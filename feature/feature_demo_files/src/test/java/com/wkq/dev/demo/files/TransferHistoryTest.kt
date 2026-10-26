package com.wkq.dev.demo.files

import com.wkq.nas.transfer.*
import org.junit.Assert.*
import org.junit.Test

class TransferHistoryTest {
    private fun task(
        id: String,
        source: String = "demo-source-input",
        target: String = "demo-target",
    ) =
        TaskSnapshot(
            id,
            "batch",
            TransferKind.COPY,
            TaskState.PAUSED,
            source,
            "input.png",
            target,
            "output.png",
            5,
            10,
            1,
            null,
        )

    @Test
    fun reenteringPageRestoresPreviouslySelectedDatabaseTask() {
        val restored = restoreDemoTaskSelection(listOf(task("newer"), task("selected")), "selected")
        assertEquals("selected", restored.selectedId)
        assertEquals(2, restored.tasks.size)
    }

    @Test
    fun otherHostTasksCannotBecomeTheSelectedDemoTask() {
        val restored =
            restoreDemoTaskSelection(
                listOf(task("other", source = "host-storage"), task("owned")),
                "other",
            )
        assertEquals("owned", restored.selectedId)
        assertEquals(listOf("owned"), restored.tasks.map { it.id })
    }

    @Test
    fun supportsBothLegacyLocalAndNewRemoteConnections() {
        val restored =
            restoreDemoTaskSelection(
                listOf(task("remote", target = "demo-target-hash"), task("legacy")),
                null,
            )
        assertEquals(listOf("remote", "legacy"), restored.tasks.map { it.id })
    }

    @Test
    fun removedSelectionFallsBackAndEmptyHistoryHasNoControllableTask() {
        assertEquals(
            "latest",
            restoreDemoTaskSelection(listOf(task("latest")), "deleted").selectedId,
        )
        assertNull(restoreDemoTaskSelection(emptyList(), "deleted").selectedId)
    }

    @Test
    fun unrelatedTargetWithSimilarPrefixIsExcluded() {
        assertTrue(
            restoreDemoTaskSelection(listOf(task("other", target = "demo-targeting")), null)
                .tasks
                .isEmpty()
        )
    }
}
