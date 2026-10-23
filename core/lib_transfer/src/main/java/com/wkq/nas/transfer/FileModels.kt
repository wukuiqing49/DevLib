package com.wkq.nas.transfer

data class SavedConnection(val id: String, val name: String, val config: ConnectionConfig)
data class StorageCapabilities(val renameDirectories: Boolean, val deleteDirectories: Boolean,
    val atomicRename: Boolean, val reliableModifiedTime: Boolean, val quota: Boolean,
    val seekRead: Boolean = false, val seekWrite: Boolean = false,
    val serverSideCopy: Boolean = false, val serverSideMove: Boolean = false,
    val overwrite: Boolean = false, val pagedListing: Boolean = false)
data class FileOperation(val id: String, val batchId: String, val connectionId: String, val kind: String,
    val sourcePath: String, val targetPath: String, val state: String, val error: TransferError?, val createdAt: Long)
data class MutationItem(val source: FileMetadata, val targetPath: String, val conflict: Boolean = false)
data class MutationPreview(val connectionId: String, val kind: String, val items: List<MutationItem>)
data class RenameRule(val prefix: String = "", val suffix: String = "", val find: String = "",
    val replacement: String = "", val startNumber: Int? = null, val keepExtension: Boolean = true)
data class StorageAnalysis(val files: Int, val directories: Int, val bytes: Long, val unknownSizes: Int,
    val types: Map<String, Long>, val freeBytes: Long?)
enum class OrganizeMode { TYPE, MONTH }

data class SavedSyncProfile(val id: String, val name: String, val sourceId: String, val sourcePath: String,
    val targetId: String, val targetPath: String, val conflictPolicy: ConflictPolicy)

data class FileOperationJob(val id: String, val state: String, val error: TransferError?)

data class FilePage(val entries: List<FileMetadata>, val total: Int)

object RenamePlanner {
    fun names(files: List<FileMetadata>, rule: RenameRule): List<String> {
        require(rule.startNumber == null || rule.startNumber >= 0)
        return files.mapIndexed { index, file ->
            val name = TransferPaths.name(file.path)
            val extension = if (rule.keepExtension && !file.directory && name.contains('.') && !name.startsWith('.'))
                ".${name.substringAfterLast('.')}" else ""
            val original = name.removeSuffix(extension)
            val stem = if (rule.find.isEmpty()) original else original.replace(rule.find, rule.replacement)
            val number = rule.startNumber?.let { Math.addExact(it, index).toString() }.orEmpty()
            validName(rule.prefix + stem + rule.suffix + number + extension)
        }
    }
    fun validName(name: String): String {
        require(name.isNotBlank() && !name.contains('/') && name != "." && name != "..")
        require(TransferPaths.normalize(name) == name)
        require(name.none { it.code < 32 })
        return name
    }
}
