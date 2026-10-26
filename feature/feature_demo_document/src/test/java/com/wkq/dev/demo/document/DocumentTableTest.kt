package com.wkq.dev.demo.document

import org.junit.Assert.*
import org.junit.Test

class DocumentTableTest {
    @Test
    fun commasAndNewlinesRemainInsideCell() {
        val table = DocumentTable(listOf(listOf("", ""))).cell(0, 1, "A,B\nC")
        assertEquals(2, table.rows.first().size)
        assertEquals("A,B\nC", table.rows[0][1])
    }

    @Test
    fun rowAndColumnEditsPreserveData() {
        val initial = DocumentTable(listOf(listOf("A", "B")))
        val result = initial.addRow().addColumn().cell(1, 2, "C").removeRow(0)
        assertEquals(listOf(listOf("", "", "C")), result.rows)
        assertEquals(listOf(listOf("A", "B")), initial.rows)
    }

    @Test
    fun cannotRemoveFinalRowOrColumn() {
        val table = DocumentTable(listOf(listOf("A")))
        assertEquals(table, table.removeRow(0).removeColumn())
    }

    @Test
    fun growthHasBounds() {
        var table = DocumentTable(listOf(listOf("")))
        repeat(40) { table = table.addRow().addColumn() }
        assertEquals(30, table.rows.size)
        assertTrue(table.rows.all { it.size == 8 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun raggedTableRejected() {
        DocumentTable(listOf(listOf("A"), listOf("B", "C")))
    }
}
