package com.wkq.nas.transfer

import java.io.IOException

enum class StorageProtocol { SMB, WEBDAV, SFTP, LOCAL, SAF }
enum class ConflictPolicy { FAIL, SKIP, KEEP_BOTH, REPLACE }
enum class TaskState { QUEUED, RUNNING, COMMITTING, RETRY_WAIT, PAUSED, SUCCEEDED, SKIPPED, FAILED, CANCELED }
enum class TransferKind { UPLOAD, DOWNLOAD, COPY, SYNC }
enum class TransferError { AUTHENTICATION, PERMISSION, NOT_FOUND, CONFLICT, SOURCE_CHANGED, NETWORK, SPACE,
    UNSUPPORTED, HOST_KEY, CERTIFICATE, STORAGE_UNAVAILABLE, VERIFICATION, INVALID_PATH, UNKNOWN }

data class TransferCleanupStatus(val id: String, val targetPath: String, val targetId: String, val error: TransferError?)

data class ConnectionConfig(
    val protocol: StorageProtocol,
    val root: String,
    val username: String = "",
    val hostKeySha256: String = ""
)

data class TransferRequest(
    val sourceId: String,
    val sourcePath: String,
    val targetId: String,
    val targetPath: String,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.FAIL,
    val kind: TransferKind = TransferKind.COPY,
    val expectedSize: Long = -1,
    val expectedModifiedAt: Long = -1,
    val verifyContent: Boolean = false,
    val targetSnapshot: FileMetadata? = null,
    val targetPreviewed: Boolean = false
)

data class FileMetadata(val path: String, val directory: Boolean, val size: Long, val modifiedAt: Long)
data class TaskSnapshot(val id: String, val batchId: String, val kind: TransferKind, val state: TaskState,
    val sourceId: String, val sourcePath: String, val targetId: String, val targetPath: String,
    val bytesTransferred: Long, val totalBytes: Long, val attempts: Int, val error: TransferError?)

data class SyncPreview(val sourceId: String, val targetId: String, val sourceDirectory: String,
    val targetDirectory: String, val items: List<SyncItem>)
data class TaskTotals(val total: Int = 0, val active: Int = 0, val completed: Int = 0, val failed: Int = 0)
enum class SyncAction { ADD, SKIP, CONFLICT }
data class SyncItem(val source: FileMetadata, val targetPath: String, val action: SyncAction, val targetSnapshot: FileMetadata? = null)

class TransferException(val error: TransferError, val retryable: Boolean = false) : IOException(error.name)

object TransferPaths {
    fun normalize(path: String): String {
        require(!path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000')) { "Invalid relative path" }
        val parts = path.split('/').filter(String::isNotEmpty)
        require(parts.none { it == ".." || it == "." }) { "Path traversal is not allowed" }
        return parts.joinToString("/")
    }
    fun join(directory: String, child: String) = normalize(listOf(directory, child).filter(String::isNotEmpty).joinToString("/"))
    fun parent(path: String) = normalize(path).substringBeforeLast('/', "")
    fun name(path: String) = normalize(path).substringAfterLast('/')
}

object SyncPlanner {
    fun plan(source: List<FileMetadata>, target: List<FileMetadata>, sourceDirectory: String, targetDirectory: String): List<SyncItem> {
        val sourceRoot = TransferPaths.normalize(sourceDirectory)
        val targetRoot = TransferPaths.normalize(targetDirectory)
        val targets = target.associateBy { TransferPaths.normalize(it.path) }
        return source.map { file ->
            val path = TransferPaths.normalize(file.path)
            require(sourceRoot.isEmpty() || path == sourceRoot || path.startsWith("$sourceRoot/"))
            val relative = if (path == sourceRoot) "" else if (sourceRoot.isEmpty()) path else path.removePrefix("$sourceRoot/")
            val destination = TransferPaths.join(targetRoot, relative)
            val existing = targets[destination]
            val same = existing != null && ((file.directory && existing.directory) ||
                (!file.directory && !existing.directory && file.size >= 0 && file.size == existing.size &&
                file.modifiedAt > 0 && existing.modifiedAt > 0 && file.modifiedAt / 1000 == existing.modifiedAt / 1000))
            SyncItem(file, destination, when { existing == null -> SyncAction.ADD; same -> SyncAction.SKIP; else -> SyncAction.CONFLICT }, existing)
        }
    }
}

/** 用户一次提交的聚合展示，不改变文件级执行与恢复检查点。 */
data class UserTaskGroup(val id: String, val family: String, val kind: String, val total: Int,
    val completed: Int, val failed: Int, val active: Int, val canceled: Int,
    val bytes: Long, val totalBytes: Long, val createdAt: Long,
    val sourceId: String = "", val targetId: String = "", val sourcePath: String = "", val targetPath: String = "", val paused: Int = 0)

/** 全批次数据库聚合，不受详情分页和失败筛选影响；项目包含目录。 */
data class BatchOutcome(val total: Int, val succeeded: Int, val skipped: Int, val failed: Int,
    val canceled: Int, val transferredBytes: Long)
