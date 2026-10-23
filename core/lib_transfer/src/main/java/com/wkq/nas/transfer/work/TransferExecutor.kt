package com.wkq.nas.transfer.work

import com.wkq.nas.transfer.*
import com.wkq.nas.transfer.data.TaskEntity
import com.wkq.nas.transfer.provider.StorageProvider
import com.wkq.nas.transfer.provider.hash
import java.security.DigestInputStream
import java.security.MessageDigest

internal class TaskStopped : RuntimeException()

internal class TransferExecutor {
    /** IO 回调由 Worker 提供，既检查协程取消，也检查数据库中的暂停/取消状态。 */
    fun execute(task: TaskEntity, source: StorageProvider, target: StorageProvider,
        check: () -> Unit, progress: (Long) -> Unit, chooseTarget: (String) -> Unit,
        beginCommit: (String) -> Unit, verifyContent: Boolean = false, prepareBackup: (String) -> Unit = {}): TaskState {
        val backup = backupPath(task)
        var savedBackupDigest = task.backupDigest
        try {
            return executeInternal(task, source, target, check, progress, chooseTarget, beginCommit, verifyContent) { digest ->
                prepareBackup(digest); savedBackupDigest = digest
            }
        } catch (error: Exception) {
            // 已转移的旧文件只在目标空缺时恢复，绝不覆盖并发创建的目标。
            if (task.conflictPolicy == ConflictPolicy.REPLACE.name) try {
                if (savedBackupDigest.isNotEmpty() && target.stat(backup) != null && target.stat(task.targetPath) == null &&
                    target.read(backup).use { hash(it) } == savedBackupDigest)
                    target.moveNew(backup, task.targetPath)
            } catch (_: Exception) { /* 旧版本仍保留在备份路径，报告原始失败。 */ }
            throw error
        }
    }

    private fun backupPath(task: TaskEntity) = TransferPaths.join(TransferPaths.parent(task.targetPath), ".nas-${task.id}.backup")

    private fun executeInternal(task: TaskEntity, source: StorageProvider, target: StorageProvider,
        check: () -> Unit, progress: (Long) -> Unit, chooseTarget: (String) -> Unit,
        beginCommit: (String) -> Unit, verifyContent: Boolean, prepareBackup: (String) -> Unit): TaskState {
        check()
        val info = source.stat(task.sourcePath) ?: throw TransferException(TransferError.NOT_FOUND)
        if (info.size != task.expectedSize || task.expectedModifiedAt > 0 && info.modifiedAt != task.expectedModifiedAt)
            throw TransferException(TransferError.SOURCE_CHANGED)
        var destination = task.targetPath
        val part = TransferPaths.join(TransferPaths.parent(destination), ".nas-${task.id}.part")
        // MOVE 已提交、Room 尚未提交时，只允许通过持久化内容哈希确认完成。
        if (task.digest.isNotEmpty() && target.stat(destination)?.size == task.expectedSize) {
            if (target.read(destination).use { hash(it, check) } == task.digest) {
                if (target.stat(part) != null) target.delete(part)
                if (task.conflictPolicy == ConflictPolicy.REPLACE.name && target.stat(backupPath(task)) != null)
                    target.delete(backupPath(task))
                return TaskState.SUCCEEDED
            }
        }
        val existing = target.stat(destination)
        if (task.conflictPolicy == ConflictPolicy.REPLACE.name && existing == null && task.targetExpectedSize >= 0 &&
            target.stat(backupPath(task)) == null) throw TransferException(TransferError.SOURCE_CHANGED)
        if (info.directory) {
            if (existing?.directory == true) return TaskState.SKIPPED
        }
        if (existing != null) {
            when (ConflictPolicy.valueOf(task.conflictPolicy)) {
                ConflictPolicy.FAIL -> throw TransferException(TransferError.CONFLICT)
                ConflictPolicy.SKIP -> return TaskState.SKIPPED
                ConflictPolicy.KEEP_BOTH -> {
                    destination = availableName(task, target, info.directory)
                    chooseTarget(destination)
                }
                ConflictPolicy.REPLACE -> {
                    if (info.directory || existing.directory) throw TransferException(TransferError.UNSUPPORTED)
                    if (task.targetExpectedSize < 0 || existing.size != task.targetExpectedSize ||
                        task.targetExpectedModified > 0 && existing.modifiedAt != task.targetExpectedModified)
                        throw TransferException(TransferError.SOURCE_CHANGED)
                }
            }
        }
        target.mkdirs(TransferPaths.parent(destination))
        if (info.directory) { check(); target.mkdirs(destination); return TaskState.SUCCEEDED }
        if (info.size < 0) throw TransferException(TransferError.UNSUPPORTED)
        // 本地/SAF 提交需要第二份完整副本；未知配额不冒充空间充足。
        target.freeBytes(TransferPaths.parent(destination))?.let { free ->
            val copies = if (target.capabilities.serverSideMove) 1 else 2
            if (info.size > free / copies) throw TransferException(TransferError.SPACE)
        }
        val temporary = TransferPaths.join(TransferPaths.parent(destination), ".nas-${task.id}.part")
        if (target.stat(temporary) != null) target.delete(temporary)
        val digest = MessageDigest.getInstance("SHA-256")
        var transferred = 0L
        source.read(task.sourcePath).use { stream ->
            DigestInputStream(stream, digest).use { input -> target.write(temporary, input, info.size) { bytes ->
                check(); transferred = bytes; progress(bytes)
            } }
        }
        check()
        val sourceAfter = source.stat(task.sourcePath) ?: throw TransferException(TransferError.SOURCE_CHANGED)
        if (sourceAfter.size != info.size || info.modifiedAt > 0 && sourceAfter.modifiedAt != info.modifiedAt)
            throw TransferException(TransferError.SOURCE_CHANGED)
        if (transferred != info.size || target.stat(temporary)?.size != info.size) throw TransferException(TransferError.VERIFICATION, true)
        val contentHash = digest.digest().joinToString("") { "%02x".format(it) }
        if ((verifyContent || task.conflictPolicy == ConflictPolicy.REPLACE.name) && target.read(temporary).use { hash(it, check) } != contentHash)
            throw TransferException(TransferError.VERIFICATION, true)
        beginCommit(contentHash)
        if (task.conflictPolicy == ConflictPolicy.REPLACE.name) {
            val current = target.stat(destination)
            val backup = backupPath(task)
            if (current != null) {
                if (current.directory || current.size != task.targetExpectedSize ||
                    task.targetExpectedModified > 0 && current.modifiedAt != task.targetExpectedModified)
                    throw TransferException(TransferError.SOURCE_CHANGED)
                val originalDigest = target.read(destination).use { hash(it, check) }
                if (target.stat(destination) != current) throw TransferException(TransferError.SOURCE_CHANGED)
                prepareBackup(originalDigest)
                if (target.stat(backup) != null && target.read(backup).use { hash(it, check) } != originalDigest) {
                    if (target.read(destination).use { hash(it, check) } != originalDigest)
                        throw TransferException(TransferError.SOURCE_CHANGED)
                    target.delete(backup)
                }
                if (target.stat(backup) != null) {
                    if (target.stat(destination) != current) throw TransferException(TransferError.SOURCE_CHANGED)
                    target.delete(destination)
                } else if (target.capabilities.serverSideMove) target.moveNew(destination, backup, check)
                else {
                    // 复制型 Provider 先验证备份，再移除旧目标，防止备份复制失败损伤旧文件。
                    val oldDigest = MessageDigest.getInstance("SHA-256")
                    target.read(destination).use { stream -> DigestInputStream(stream, oldDigest).use { input ->
                        target.write(backup, input, current.size) { check() }
                    } }
                    val expected = oldDigest.digest().joinToString("") { "%02x".format(it) }
                    if (expected != originalDigest || target.stat(backup)?.size != current.size || target.read(backup).use { hash(it, check) } != expected)
                        throw TransferException(TransferError.VERIFICATION)
                    if (target.stat(destination) != current) throw TransferException(TransferError.SOURCE_CHANGED)
                    target.delete(destination)
                }
            }
        }
        target.moveNew(temporary, destination, check)
        if (target.stat(destination)?.size != info.size) throw TransferException(TransferError.VERIFICATION, true)
        if ((verifyContent || task.conflictPolicy == ConflictPolicy.REPLACE.name) && target.read(destination).use { hash(it, check) } != contentHash)
            throw TransferException(TransferError.VERIFICATION, true)
        if (task.conflictPolicy == ConflictPolicy.REPLACE.name && target.stat(backupPath(task)) != null)
            target.delete(backupPath(task))
        // 时间戳不支持时保留实际值；同步预览不会把未知时间当作相同文件。
        try { target.setModified(destination, info.modifiedAt) } catch (_: Exception) { }
        return TaskState.SUCCEEDED
    }

    private fun availableName(task: TaskEntity, target: StorageProvider, directory: Boolean): String {
        val parent = TransferPaths.parent(task.requestedTargetPath)
        val name = TransferPaths.name(task.requestedTargetPath)
        val extension = if (!directory && name.contains('.') && !name.startsWith('.')) ".${name.substringAfterLast('.')}" else ""
        val stem = if (extension.isEmpty()) name else name.removeSuffix(extension)
        for (index in 1..10000) {
            val candidate = TransferPaths.join(parent, "$stem ($index)$extension")
            if (target.stat(candidate) == null) return candidate
        }
        throw TransferException(TransferError.CONFLICT)
    }
}
