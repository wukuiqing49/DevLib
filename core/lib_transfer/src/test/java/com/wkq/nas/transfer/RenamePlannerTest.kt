package com.wkq.nas.transfer

import org.junit.Assert.*
import org.junit.Test

class RenamePlannerTest {
    @Test fun rulesPreserveExtensionsAndUseStableNumbering() {
        val files = listOf(FileMetadata("a/report.pdf", false, 10, 1), FileMetadata("a/report.txt", false, 0, 1))
        assertEquals(listOf("job_quote_5.pdf", "job_quote_6.txt"), RenamePlanner.names(files,
            RenameRule("job_", "_", "report", "quote", 5)))
    }
    @Test fun directoriesAndDotFilesAreNotSplitAsExtensions() {
        assertEquals(listOf("x.config", "xfolder.name"), RenamePlanner.names(listOf(
            FileMetadata(".config", false, 0, 1), FileMetadata("folder.name", true, 0, 1)), RenameRule(prefix = "x")))
    }
    @Test fun invalidNamesAndOverflowAreRejected() {
        listOf("../x", "a/b", "", "  ", "a\\b", "a\n").forEach {
            try { RenamePlanner.validName(it); fail(it) } catch (_: IllegalArgumentException) { }
        }
        try {
            RenamePlanner.names(listOf(FileMetadata("a", false, 0, 1), FileMetadata("b", false, 0, 1)), RenameRule(startNumber = Int.MAX_VALUE))
            fail("Number overflow")
        } catch (_: ArithmeticException) { }
    }
}
