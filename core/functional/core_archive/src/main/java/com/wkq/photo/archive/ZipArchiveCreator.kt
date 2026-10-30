package com.wkq.photo.archive

import com.wkq.photo.core.utils.AtomicFileOutput
import net.lingala.zip4j.io.outputstream.ZipOutputStream as Zip4jOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream as JavaZipOutputStream

data class ZipArchiveRequest(
    val sourceFiles: List<File>,
    val outputFile: File
) {
    init {
        require(sourceFiles.isNotEmpty())
        require(sourceFiles.all(File::isFile)) { "All archive sources must exist" }
        require(outputFile.extension.equals("zip", ignoreCase = true)) { "Archive output must use .zip" }
    }
}

data class ZipArchiveEntry(
    val sourceFile: File,
    val entryName: String = sourceFile.name
) {
    init {
        require(sourceFile.isFile) { "Archive source must exist: ${sourceFile.absolutePath}" }
        normalizeZipEntryName(entryName)
    }
}

enum class ZipCompressionLevel(internal val deflaterLevel: Int) {
    FAST(Deflater.BEST_SPEED),
    BALANCED(Deflater.DEFAULT_COMPRESSION),
    SMALLEST(Deflater.BEST_COMPRESSION)
}

data class ZipArchiveOptions(
    val compressionLevel: ZipCompressionLevel = ZipCompressionLevel.BALANCED,
    val comment: String? = null
) {
    init {
        require(comment == null || comment.toByteArray(StandardCharsets.UTF_8).size <= MAX_ZIP_COMMENT_BYTES) {
            "ZIP comment exceeds 65535 UTF-8 bytes"
        }
    }
}

enum class ZipEncryptionStrength {
    AES_128,
    AES_256
}

/** ZIP AES 加密参数。密码由 createEncrypted 单独接收，不会进入配置对象的 toString。 */
data class ZipEncryptionOptions(
    val strength: ZipEncryptionStrength = ZipEncryptionStrength.AES_256
)

data class ZipArchiveProgress(
    val completedFiles: Int,
    val totalFiles: Int,
    val processedBytes: Long,
    val totalBytes: Long,
    val currentEntryName: String
)

/** 可取消的 ZIP 创建能力，自动处理同名文件。调用方应在后台线程执行。 */
object ZipArchiveCreator {

    fun create(
        request: ZipArchiveRequest,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): File {
        return createInternal(
            entries = request.sourceFiles.map { ZipArchiveEntry(it) },
            outputFile = request.outputFile,
            options = ZipArchiveOptions(),
            onProgress = {},
            onFileCompleted = onProgress,
            isCancelled = isCancelled
        )
    }

    fun create(
        entries: List<ZipArchiveEntry>,
        outputFile: File,
        options: ZipArchiveOptions = ZipArchiveOptions(),
        onProgress: (ZipArchiveProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): File {
        return createInternal(
            entries = entries,
            outputFile = outputFile,
            options = options,
            onProgress = onProgress,
            onFileCompleted = { _, _ -> },
            isCancelled = isCancelled
        )
    }

    /**
     * 使用 AES 创建密码 ZIP。调用结束后会清除内部密码副本，不会修改调用方持有的数组。
     */
    fun createEncrypted(
        request: ZipArchiveRequest,
        password: CharArray,
        encryptionOptions: ZipEncryptionOptions = ZipEncryptionOptions(),
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): File {
        return withPasswordCopy(password) { passwordCopy ->
            createInternal(
                entries = request.sourceFiles.map { ZipArchiveEntry(it) },
                outputFile = request.outputFile,
                options = ZipArchiveOptions(),
                encryption = Encryption(passwordCopy, encryptionOptions.strength),
                onProgress = {},
                onFileCompleted = onProgress,
                isCancelled = isCancelled
            )
        }
    }

    /**
     * 使用 AES 创建密码 ZIP，并保留自定义条目名、压缩参数、字节进度与取消能力。
     */
    fun createEncrypted(
        entries: List<ZipArchiveEntry>,
        outputFile: File,
        password: CharArray,
        options: ZipArchiveOptions = ZipArchiveOptions(),
        encryptionOptions: ZipEncryptionOptions = ZipEncryptionOptions(),
        onProgress: (ZipArchiveProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): File {
        return withPasswordCopy(password) { passwordCopy ->
            createInternal(
                entries = entries,
                outputFile = outputFile,
                options = options,
                encryption = Encryption(passwordCopy, encryptionOptions.strength),
                onProgress = onProgress,
                onFileCompleted = { _, _ -> },
                isCancelled = isCancelled
            )
        }
    }

    private fun createInternal(
        entries: List<ZipArchiveEntry>,
        outputFile: File,
        options: ZipArchiveOptions,
        encryption: Encryption? = null,
        onProgress: (ZipArchiveProgress) -> Unit,
        onFileCompleted: (completed: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean
    ): File {
        require(entries.isNotEmpty())
        val output = outputFile.canonicalFile
        require(entries.none { it.sourceFile.canonicalFile == output }) { "Archive output must not also be a source file" }
        val totalBytes = entries.fold(0L) { total, entry -> saturatingAdd(total, entry.sourceFile.length()) }
        var processedBytes = 0L
        val names = mutableSetOf<String>()
        AtomicFileOutput.write(outputFile) { temporary ->
            openArchive(temporary, options.compressionLevel, encryption).use { archive ->
                options.comment?.let(archive::setComment)
                entries.forEachIndexed { index, entry ->
                    check(!isCancelled()) { "Archive creation cancelled" }
                    val source = entry.sourceFile
                    val entryName = uniqueEntryName(normalizeZipEntryName(entry.entryName), names)
                    archive.putNextEntry(entryName, source)
                    try {
                        BufferedInputStream(source.inputStream()).use { input ->
                            val buffer = ByteArray(COPY_BUFFER_SIZE)
                            while (true) {
                                check(!isCancelled()) { "Archive creation cancelled" }
                                val read = input.read(buffer)
                                if (read < 0) break
                                archive.write(buffer, 0, read)
                                processedBytes = saturatingAdd(processedBytes, read.toLong())
                                onProgress(
                                    ZipArchiveProgress(
                                        completedFiles = index,
                                        totalFiles = entries.size,
                                        processedBytes = processedBytes,
                                        totalBytes = totalBytes,
                                        currentEntryName = entryName
                                    )
                                )
                            }
                        }
                    } finally {
                        archive.closeEntry()
                    }
                    onProgress(
                        ZipArchiveProgress(
                            completedFiles = index + 1,
                            totalFiles = entries.size,
                            processedBytes = processedBytes,
                            totalBytes = totalBytes,
                            currentEntryName = entryName
                        )
                    )
                    onFileCompleted(index + 1, entries.size)
                }
            }
        }
        return outputFile
    }

    private fun openArchive(
        outputFile: File,
        compressionLevel: ZipCompressionLevel,
        encryption: Encryption?
    ): ArchiveOutput {
        val output = outputFile.outputStream().buffered()
        return try {
            if (encryption == null) {
                JavaArchiveOutput(output, compressionLevel)
            } else {
                AesArchiveOutput(output, compressionLevel, encryption)
            }
        } catch (error: Throwable) {
            output.close()
            throw error
        }
    }

    private fun uniqueEntryName(original: String, existing: MutableSet<String>): String {
        if (existing.add(original)) return original
        val parent = original.substringBeforeLast('/', "").takeIf(String::isNotEmpty)
        val fileName = original.substringAfterLast('/')
        val extensionStart = fileName.lastIndexOf('.').takeIf { it > 0 }
        val name = extensionStart?.let { fileName.substring(0, it) } ?: fileName
        val extension = extensionStart?.let { fileName.substring(it + 1) }
        var sequence = 2
        while (true) {
            val candidate = buildString {
                parent?.let { append(it).append('/') }
                append(name)
                append(" (")
                append(sequence)
                append(')')
                extension?.let { append('.').append(it) }
            }
            if (existing.add(candidate)) return candidate
            sequence++
        }
    }

    private const val COPY_BUFFER_SIZE = 64 * 1024
}

private interface ArchiveOutput : Closeable {
    fun setComment(comment: String)
    fun putNextEntry(entryName: String, sourceFile: File)
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun closeEntry()
}

private class JavaArchiveOutput(
    output: OutputStream,
    compressionLevel: ZipCompressionLevel
) : ArchiveOutput {
    private val archive = JavaZipOutputStream(output).apply {
        setLevel(compressionLevel.deflaterLevel)
    }

    override fun setComment(comment: String) = archive.setComment(comment)

    override fun putNextEntry(entryName: String, sourceFile: File) {
        archive.putNextEntry(ZipEntry(entryName).apply {
            sourceFile.lastModified().takeIf { it > 0L }?.let { time = it }
        })
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        archive.write(buffer, offset, length)
    }

    override fun closeEntry() = archive.closeEntry()

    override fun close() = archive.close()
}

private class AesArchiveOutput(
    output: OutputStream,
    compressionLevel: ZipCompressionLevel,
    encryption: Encryption
) : ArchiveOutput {
    private val archive = Zip4jOutputStream(output, encryption.password, StandardCharsets.UTF_8)
    private val parameters = ZipParameters().apply {
        isEncryptFiles = true
        encryptionMethod = EncryptionMethod.AES
        aesKeyStrength = when (encryption.strength) {
            ZipEncryptionStrength.AES_128 -> AesKeyStrength.KEY_STRENGTH_128
            ZipEncryptionStrength.AES_256 -> AesKeyStrength.KEY_STRENGTH_256
        }
        this.compressionLevel = when (compressionLevel) {
            ZipCompressionLevel.FAST -> CompressionLevel.FASTEST
            ZipCompressionLevel.BALANCED -> CompressionLevel.HIGHER
            ZipCompressionLevel.SMALLEST -> CompressionLevel.ULTRA
        }
    }

    override fun setComment(comment: String) = archive.setComment(comment)

    override fun putNextEntry(entryName: String, sourceFile: File) {
        parameters.fileNameInZip = entryName
        parameters.lastModifiedFileTime = sourceFile.lastModified().takeIf { it > 0L } ?: 0L
        archive.putNextEntry(parameters)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        archive.write(buffer, offset, length)
    }

    override fun closeEntry() {
        archive.closeEntry()
    }

    override fun close() = archive.close()
}

private class Encryption(
    val password: CharArray,
    val strength: ZipEncryptionStrength
)

private inline fun <T> withPasswordCopy(password: CharArray, block: (CharArray) -> T): T {
    val passwordCopy = password.copyOf()
    return try {
        require(passwordCopy.size in MIN_ZIP_PASSWORD_LENGTH..MAX_ZIP_PASSWORD_LENGTH) {
            "ZIP password length must be between $MIN_ZIP_PASSWORD_LENGTH and $MAX_ZIP_PASSWORD_LENGTH characters"
        }
        block(passwordCopy)
    } finally {
        passwordCopy.fill('\u0000')
    }
}

internal const val MIN_ZIP_PASSWORD_LENGTH = 4
internal const val MAX_ZIP_PASSWORD_LENGTH = 128

private fun normalizeZipEntryName(value: String): String {
    val normalized = value.replace('\\', '/').trim('/')
    require(normalized.isNotBlank()) { "ZIP entry name must not be blank" }
    require(!value.startsWith('/') && !value.startsWith('\\')) { "ZIP entry name must be relative" }
    require(!normalized.matches(Regex("^[A-Za-z]:.*"))) { "ZIP entry name must not contain a drive prefix" }
    require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "ZIP entry name contains an unsafe path segment"
    }
    return normalized
}

private fun saturatingAdd(first: Long, second: Long): Long {
    return if (second > 0 && first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
}

private const val MAX_ZIP_COMMENT_BYTES = 65_535
