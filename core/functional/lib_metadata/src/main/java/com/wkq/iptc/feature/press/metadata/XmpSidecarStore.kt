package com.wkq.iptc.feature.press.metadata

import com.wkq.dev.metadata.model.MetadataTemplate
import java.io.File
import java.util.UUID

/** 生成待交付的附属文件，原图仅用于确定名称，不修改其内容。 */
object XmpSidecarStore {
    fun create(
        sourceFile: File,
        originalName: String,
        template: MetadataTemplate,
        outputDirectory: File,
        existingPacket: String = ""
    ): File {
        require(sourceFile.isFile) { "Source photo is unavailable" }
        require(originalName.isNotBlank() && originalName != "." && originalName != ".." &&
            originalName.none { it == '/' || it == '\\' || it.code < 32 }) {
            "Invalid source name"
        }
        val baseName = originalName.substringBeforeLast('.', originalName)
            .ifBlank { originalName }
        val directory = File(outputDirectory, UUID.randomUUID().toString())
        check(directory.mkdirs()) { "Cannot create sidecar directory" }
        val sidecar = File(directory, "$baseName.xmp")
        try {
            val packet = XmpWriter.buildSidecarPacket(template, existingPacket, sourceFile)
            sidecar.outputStream().use { stream ->
                stream.write(packet.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(MetadataVerifier.verifySidecar(sidecar, template)) {
                "Sidecar verification failed"
            }
            return sidecar
        } catch (error: Throwable) {
            sidecar.delete()
            directory.delete()
            throw error
        }
    }
}
