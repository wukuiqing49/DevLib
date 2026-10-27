package com.wkq.dev.demo.common

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WorkspacePolicyTest {
    @Test
    fun importedNameCannotEscapeDirectory() {
        assertEquals("my photo.jpg", WorkspacePolicy.importName("my photo.jpg", "jpg"))
        assertFalse(WorkspacePolicy.importName("../../private.jpg", "jpg").contains('/'))
        assertEquals("file.png", WorkspacePolicy.importName(null, "png"))
    }

    @Test
    fun siblingPrefixIsNotManaged() {
        val root = File("workspace/shared/demo")
        assertFalse(WorkspacePolicy.managed(root, File("workspace/shared/demo-foreign/x.jpg")))
        assertFalse(WorkspacePolicy.managed(root, root))
    }

    @Test
    fun canonicalEscapeRejected() {
        assertFalse(
            WorkspacePolicy.managed(
                File("workspace/shared/demo"),
                File("workspace/shared/demo/../secret.jpg"),
            )
        )
    }

    @Test
    fun nestedOutputIsManaged() {
        assertTrue(
            WorkspacePolicy.managed(
                File("workspace/shared/demo"),
                File("workspace/shared/demo/archive/item.png"),
            )
        )
    }

    @Test
    fun documentCannotBePassedToImageEngine() {
        assertFalse(WorkspacePolicy.accepts("/image/core_image_render", "pdf"))
        assertFalse(WorkspacePolicy.accepts("/document/core_pdf", "docx"))
        assertTrue(WorkspacePolicy.accepts("/image/core_image_render", "JPG"))
    }

    @Test
    fun genericFileToolsAcceptDocuments() {
        assertTrue(WorkspacePolicy.accepts("/files/core_nas", "docx"))
        assertTrue(WorkspacePolicy.accepts("/files/core_archive", "zip"))
    }

    @Test
    fun unsupportedRouteNotOffered() {
        assertFalse(WorkspacePolicy.accepts("/google/core_google", "png"))
        assertFalse(WorkspacePolicy.accepts("/unknown", "jpg"))
    }
}
