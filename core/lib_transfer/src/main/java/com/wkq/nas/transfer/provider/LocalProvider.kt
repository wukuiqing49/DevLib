package com.wkq.nas.transfer.provider

import com.wkq.nas.transfer.*
import java.io.File
import java.io.InputStream

internal class LocalProvider(rootPath: String) : StorageProvider {
    override val capabilities = StorageCapabilities(true, true, false, true, true)
    private fun realFile(file: File): File = if (android.os.Build.VERSION.SDK_INT >= 26) file.toPath().toRealPath().toFile()
        else file.canonicalFile
    private val root = File(rootPath).let {
        if (!it.exists()) throw TransferException(TransferError.NOT_FOUND)
        realFile(it)
    }
    private fun symbolic(file: File): Boolean = if (android.os.Build.VERSION.SDK_INT >= 26) java.nio.file.Files.isSymbolicLink(file.toPath())
        else if (file.exists()) android.system.OsConstants.S_ISLNK(android.system.Os.lstat(file.path).st_mode) else false
    init { require(root.isDirectory) { "Local root must be an existing directory" } }
    private fun file(path: String): File {
        val candidate = File(root, TransferPaths.normalize(path)).absoluteFile
        var component = root
        TransferPaths.normalize(path).split('/').filter(String::isNotEmpty).forEach {
            component = File(component, it)
            require(!symbolic(component)) { "Symbolic links are not supported" }
        }
        var existing = candidate
        while (!existing.exists()) existing = existing.parentFile ?: throw TransferException(TransferError.STORAGE_UNAVAILABLE)
        val resolvedParent = realFile(existing)
        val suffix = candidate.path.removePrefix(existing.path).trimStart(File.separatorChar)
        val resolved = if (suffix.isEmpty()) resolvedParent else File(resolvedParent, suffix)
        require(resolved == root || resolved.path.startsWith(root.path + File.separator)) { "Path escapes root" }
        return resolved
    }
    override fun stat(path: String): FileMetadata? = file(path).takeIf { it.exists() }?.let {
        FileMetadata(TransferPaths.normalize(path), it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified())
    }
    override fun list(path: String): List<FileMetadata> {
        val directory = file(path)
        if (!directory.isDirectory) throw TransferException(TransferError.NOT_FOUND)
        return (directory.listFiles() ?: throw TransferException(TransferError.PERMISSION)).filterNot(::symbolic).map {
            val relative = TransferPaths.join(path, it.name)
            stat(relative) ?: throw TransferException(TransferError.NOT_FOUND)
        }
    }
    override fun read(path: String) = file(path).inputStream()
    override fun visit(path: String, accept: (FileMetadata) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 26) { list(path).forEach(accept); return }
        val directory = file(path)
        if (!directory.isDirectory) throw TransferException(TransferError.NOT_FOUND)
        java.nio.file.Files.newDirectoryStream(directory.toPath()).use { stream -> stream.forEach { child ->
            if (!symbolic(child.toFile())) stat(TransferPaths.join(path, child.fileName.toString()))?.let(accept)
        } }
    }
    override fun mkdirs(path: String) {
        val directory = file(path)
        if (!directory.isDirectory && !directory.mkdirs()) throw TransferException(TransferError.PERMISSION)
    }
    override fun write(path: String, input: InputStream, size: Long, progress: (Long) -> Unit) {
        val target = file(path)
        if (!target.createNewFile()) throw TransferException(TransferError.CONFLICT)
        target.outputStream().use { output -> copyBytes(input, output, progress); output.fd.sync() }
    }
    override fun moveNew(source: String, target: String, check: () -> Unit) {
        val destination = file(target)
        if (destination.exists()) throw TransferException(TransferError.CONFLICT)
        val original = file(source)
        if (original.isDirectory) {
            check()
            if (!destination.mkdir()) throw TransferException(TransferError.CONFLICT)
            try {
                check()
                // 目标为空的本次专属预留目录；rmdir/rename 不会删除并发创建的子文件。
                android.system.Os.rename(original.path, destination.path)
            } catch (error: Exception) { destination.delete(); throw error }
            return
        }
        // createNewFile 提供排他保留，避免 renameTo 覆盖其他任务或进程创建的目标。
        if (!destination.createNewFile()) throw TransferException(TransferError.CONFLICT)
        try {
            file(source).inputStream().use { input -> destination.outputStream().use { output ->
                copyBytes(input, output) { check() }; output.fd.sync()
            } }
            if (!file(source).delete()) throw TransferException(TransferError.PERMISSION)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }
    override fun delete(path: String) {
        val target = file(path)
        require(target != root && !target.isDirectory)
        if (target.exists() && !target.delete()) throw TransferException(TransferError.PERMISSION)
    }
    override fun setModified(path: String, time: Long) { if (time > 0) file(path).setLastModified(time) }
    override fun deleteEmptyDirectory(path: String) {
        val target = file(path)
        require(target != root && target.isDirectory)
        if (!target.delete()) throw TransferException(TransferError.CONFLICT)
    }
    override fun freeBytes(path: String) = file(path).usableSpace
}
