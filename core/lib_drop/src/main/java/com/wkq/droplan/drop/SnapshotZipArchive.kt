package com.wkq.droplan.drop

import java.io.*
import java.security.MessageDigest
import java.util.zip.*

/** 应用受控目录内预生成并校验，完成前不暴露；JDK/Android ZIP 自动使用 ZIP64 长度。 */
class SnapshotZipArchive private constructor(private val file: File, override val contentVersion: String) : ShareSource, Closeable {
    override val id = ShareEngine.newFileId()
    override val displayName = "DropLAN.zip"
    override val mimeType = "application/zip"
    override val length: Long = file.length()
    override val supportsRandomAccess = true
    override fun openStream(): InputStream = FileInputStream(file)
    override fun openRange(offset: Long, length: Long): InputStream {
        require(offset >= 0 && length >= 0 && offset <= this.length && length <= this.length - offset)
        val input = RandomAccessFile(file, "r")
        try { input.seek(offset) } catch (e: Exception) { input.close(); throw e }
        return object : InputStream() {
            private var remaining = length
            override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (remaining == 0L) return -1
                val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
                if (n < 0) throw EOFException()
                remaining -= n; return n
            }
            override fun close() = input.close()
        }
    }
    override fun close() { file.delete(); file.parentFile?.delete() }

    enum class Mode { STORE, DEFLATE }
    companion object {
        /** quota 同时限制扫描输入、解压验证与最终输出，防未知长度源耗尽空间。 */
        fun prepare(directory: File, sources: List<ShareSource>, mode: Mode, quota: Long,
                    stage: (String) -> Unit = {},
                    cancelled: () -> Boolean = { Thread.currentThread().isInterrupted }): SnapshotZipArchive {
            require(sources.isNotEmpty() && sources.size <= 999 && quota > 0)
            check(directory.mkdirs()) { "archive_directory_unavailable" }
            val partial = File(directory, "preparing.zip")
            val completed = File(directory, "bundle.zip")
            var total = 0L
            fun checkCancelled() { if (cancelled()) throw InterruptedIOException("archive_cancelled") }
            fun scan(source: ShareSource): Pair<Long, Long> {
                var size = 0L; val crc = CRC32(); val buffer = ByteArray(64 * 1024)
                source.openStream().use { input ->
                    while (true) {
                        checkCancelled(); val n = input.read(buffer); if (n < 0) break
                        if (n == 0 || size > quota - n) throw IOException("archive_input_limit")
                        crc.update(buffer, 0, n); size += n
                    }
                }
                if (source.length != null && size != source.length) throw IOException("source_changed")
                return size to crc.value
            }
            try {
                stage("zip")
                val bounded = object : FilterOutputStream(FileOutputStream(partial)) {
                    private var bytes = 0L
                    override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        checkCancelled()
                        if (bytes > quota - len) throw IOException("archive_space_limit")
                        out.write(b, off, len); bytes += len
                    }
                }
                ZipOutputStream(BufferedOutputStream(bounded, 64 * 1024)).use { zip ->
                    zip.setLevel(1)
                    sources.forEachIndexed { index, source ->
                        checkCancelled()
                        fun safe(name: String) = name.map { if (it < ' ' || it in ":*?\"<>|" || it == '\u007f') '_' else it }
                            .joinToString("").trim(' ', '.').take(180).ifEmpty { "file" }
                        val segments = source.archivePath?.split('/', '\\')?.takeIf { it.size in 1..33 && it.sumOf(String::length) <= 6144 }
                            ?: listOf(source.displayName.substringAfterLast('/').substringAfterLast('\\'))
                        val path = segments.dropLast(1).joinToString("/") { safe(it) }
                        val name = "${index + 1}-${safe(segments.last())}"
                        val entry = ZipEntry(if (path.isEmpty()) name else "$path/$name").apply { time = 0 }
                        if (mode == Mode.STORE) {
                            val (size, crc) = scan(source)
                            entry.method = ZipEntry.STORED; entry.size = size; entry.compressedSize = size; entry.crc = crc
                        }
                        zip.putNextEntry(entry)
                        var size = 0L; val buffer = ByteArray(64 * 1024)
                        source.openStream().use { input ->
                            while (true) {
                                checkCancelled(); val n = input.read(buffer); if (n < 0) break
                                if (n == 0 || total > quota - n) throw IOException("archive_input_limit")
                                zip.write(buffer, 0, n); size += n; total += n
                            }
                        }
                        if (source.length != null && size != source.length) throw IOException("source_changed")
                        zip.closeEntry()
                    }
                }
                // 完整读回 CRC/长度，防损坏与未完成包发布；不把全文件放入内存。
                stage("verify")
                ZipFile(partial).use { zip ->
                    val entries = zip.entries(); var count = 0; var checked = 0L
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement(); count++
                        val crc = CRC32(); var size = 0L; val buffer = ByteArray(64 * 1024)
                        zip.getInputStream(entry).use { input ->
                            while (true) {
                                checkCancelled(); val n = input.read(buffer); if (n < 0) break
                                if (n == 0 || checked > quota - n) throw IOException("archive_validation_limit")
                                checked += n; size += n; crc.update(buffer, 0, n)
                            }
                        }
                        if (size != entry.size || crc.value != entry.crc) throw IOException("archive_invalid")
                    }
                    check(count == sources.size)
                }
                stage("hash")
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(partial).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { checkCancelled(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                checkCancelled(); check(partial.renameTo(completed))
                return SnapshotZipArchive(completed, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            } catch (error: Exception) { partial.delete(); completed.delete(); directory.delete(); throw error }
        }
    }
}
