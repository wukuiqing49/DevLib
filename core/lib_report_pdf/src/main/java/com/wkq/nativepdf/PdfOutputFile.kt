package com.wkq.nativepdf

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.FilterOutputStream

/** 写完后在同一目录替换；失败时保留已有 PDF。 */
internal fun writePdfOutput(
    outputFile: File,
    checkActive: () -> Unit = {},
    writeDocument: (OutputStream) -> Unit
): File {
    checkActive()
    val outputDir = outputFile.absoluteFile.parentFile
    check(outputDir == null || outputDir.isDirectory || outputDir.mkdirs()) {
        "无法创建 PDF 输出目录"
    }
    val temporaryFile = File.createTempFile("pdf_", ".tmp", outputDir)
    try {
        FileOutputStream(temporaryFile).use { output ->
            writeDocument(object : FilterOutputStream(output) {
                override fun write(value: Int) {
                    checkActive()
                    out.write(value)
                }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    checkActive()
                    out.write(buffer, offset, length)
                }
            })
            checkActive()
            output.flush()
            output.fd.sync()
        }
        check(temporaryFile.length() > 0L) { "PDF 文件生成失败" }
        checkActive()
        // 不先删除旧文件；同目录 rename 失败时旧版本仍然可用。
        check(temporaryFile.renameTo(outputFile)) { "无法完成 PDF 文件写入" }
        return outputFile
    } finally {
        temporaryFile.delete()
    }
}
