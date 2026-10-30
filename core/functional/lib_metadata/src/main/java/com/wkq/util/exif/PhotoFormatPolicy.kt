package com.wkq.util.exif

import java.io.File
import java.io.InputStream

/** 按真实文件头识别格式；读取能力不代表已开放原格式编辑能力。 */
object PhotoFormatPolicy {
    enum class Format(val extension: String, val mimeType: String, val extensions: Set<String>) {
        JPEG("jpg", "image/jpeg", setOf("jpg", "jpeg")),
        PNG("png", "image/png", setOf("png")),
        WEBP("webp", "image/webp", setOf("webp")),
        UNKNOWN("", "application/octet-stream", emptySet())
    }

    enum class Operation { ENCAPSULATE, SCRUB }

    fun detect(file: File): Format = file.inputStream().use(::detect)

    fun detect(input: InputStream): Format {
        val header = ByteArray(12)
        var size = 0
        while (size < header.size) {
            val read = input.read(header, size, header.size - size)
            if (read < 0) break
            if (read == 0) {
                val next = input.read()
                if (next < 0) break
                header[size++] = next.toByte()
            } else size += read
        }
        return when {
            size >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() &&
                header[2] == 0xff.toByte() -> Format.JPEG
            size >= 8 && header.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> Format.PNG
            size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> Format.WEBP
            else -> Format.UNKNOWN
        }
    }

    /** 格式级能力；具体容器结构和载荷还需在写入前后严格校验。 */
    fun supportsNative(format: Format, operation: Operation): Boolean = when (operation) {
        Operation.ENCAPSULATE, Operation.SCRUB -> format in setOf(Format.JPEG, Format.PNG, Format.WEBP)
    }

    fun outputName(originalName: String?, format: Format, fallbackName: String): String {
        require(format != Format.UNKNOWN) { "Unsupported output format" }
        val safe = (originalName?.takeIf { it.isNotBlank() } ?: fallbackName)
            .map { if (it == '/' || it == '\\' || it.code < 32 || it in ":*?\"<>|") '_' else it }
            .joinToString("").trim().trimEnd('.')
        val name = safe.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "photo"
        val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        // 合法且与内容一致时保留大小写；转换或错误扩展名必须使用真实输出格式。
        if (extension in format.extensions && name.substringBeforeLast('.').isNotBlank()) return name
        val stem = name.substringBeforeLast('.', name).takeIf { it.isNotBlank() } ?: "photo"
        return "$stem.${format.extension}"
    }
}
