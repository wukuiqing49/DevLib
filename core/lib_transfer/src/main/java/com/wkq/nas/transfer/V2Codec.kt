package com.wkq.nas.transfer

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal object V2Codec {
    fun stamp(value: ContentStamp?) = value?.let { JSONObject().put("path", it.metadata.path)
        .put("directory", it.metadata.directory).put("size", it.metadata.size).put("modified", it.metadata.modifiedAt).put("digest", it.digest) }
    fun stamp(value: JSONObject?) = value?.let { ContentStamp(FileMetadata(it.getString("path"), it.getBoolean("directory"),
        it.getLong("size"), it.getLong("modified")), it.getString("digest")) }
    fun profile(p: AdvancedSyncProfile) = JSONObject().put("id", p.id).put("name", p.name).put("left", p.leftId)
        .put("leftRoot", p.leftRoot).put("right", p.rightId).put("rightRoot", p.rightRoot).put("mode", p.mode.name)
        .put("interval", p.intervalMinutes).put("unmetered", p.unmetered).put("charging", p.charging)
        .put("selected", JSONArray(p.selectedPaths.sorted())).put("deviceTrigger", p.deviceTrigger)
    fun profile(p: JSONObject) = AdvancedSyncProfile(p.getString("id"), p.getString("name"), p.getString("left"),
        p.getString("leftRoot"), p.getString("right"), p.getString("rightRoot"), SyncMode.valueOf(p.getString("mode")),
        p.getLong("interval"), p.getBoolean("unmetered"), p.getBoolean("charging"),
        p.optJSONArray("selected")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(), p.optBoolean("deviceTrigger"))
    fun preview(p: AdvancedSyncPreview) = JSONObject().put("profile", profile(p.profile)).put("items", JSONArray().apply {
        p.items.forEach { put(JSONObject().put("path", it.relative).put("left", stamp(it.left))
            .put("right", stamp(it.right)).put("action", it.action.name)) }
    })
    fun preview(p: JSONObject): AdvancedSyncPreview {
        val items = p.getJSONArray("items")
        return AdvancedSyncPreview(profile(p.getJSONObject("profile")), (0 until items.length()).map {
            val item = items.getJSONObject(it)
            AdvancedSyncItem(item.getString("path"), stamp(item.optJSONObject("left")), stamp(item.optJSONObject("right")),
                AdvancedAction.valueOf(item.getString("action")))
        })
    }
    fun recycle(p: RecycleEntry) = JSONObject().put("id", p.id).put("connection", p.connectionId).put("original", p.originalPath)
        .put("stored", p.storedPath).put("digest", p.digest).put("size", p.size).put("created", p.createdAt).put("state", p.state).put("directory", p.directory)
    fun recycle(p: JSONObject) = RecycleEntry(p.getString("id"), p.getString("connection"), p.getString("original"),
        p.getString("stored"), p.getString("digest"), p.getLong("size"), p.getLong("created"), p.getString("state"), p.optBoolean("directory"))
}

/** 配置交换只接受明确的公开字段；不导出密码、设备本地路径或 SAF 授权。 */
object ConnectionExchange {
    const val MAX_CONNECTIONS = 1000
    const val MAX_BYTES = 1024 * 1024
    fun encode(connections: List<SavedConnection>): String {
        val remote = connections.filter { it.config.protocol in setOf(StorageProtocol.SMB, StorageProtocol.SFTP, StorageProtocol.WEBDAV) }
        if (remote.isEmpty()) throw TransferException(TransferError.NOT_FOUND)
        require(remote.size <= MAX_CONNECTIONS)
        val text = JSONObject().put("version", 1).put("connections", JSONArray().apply {
        remote.forEach {
            val c = it.config
            put(JSONObject().put("name", it.name).put("protocol", c.protocol.name).put("root", c.root)
                .put("username", c.username).put("hostKey", c.hostKeySha256))
        }
        }).toString(2)
        // 合法导出必须满足自身导入约束；超限不截断，也不产生无法导回的文件。
        decode(text)
        return text
    }
    fun decode(text: String): List<Pair<String, ConnectionConfig>> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JSONObject(text)
        require(root.getInt("version") == 1 && root.keys().asSequence().toSet() == setOf("version", "connections"))
        val rows = root.getJSONArray("connections")
        require(rows.length() in 1..MAX_CONNECTIONS)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("name", "protocol", "root", "username", "hostKey"))
            val name = row.getString("name"); require(name.isNotBlank() && name.length <= 200)
            val p = StorageProtocol.valueOf(row.getString("protocol"))
            require(p in setOf(StorageProtocol.SMB, StorageProtocol.SFTP, StorageProtocol.WEBDAV))
            val uri = URI(row.getString("root"))
            require(uri.scheme == when (p) { StorageProtocol.SMB -> "smb"; StorageProtocol.SFTP -> "sftp"; else -> "https" })
            require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
            require(uri.port == -1 || uri.port in 1..65535)
            TransferPaths.normalize(uri.path.orEmpty().trim('/'))
            require(p != StorageProtocol.SMB || uri.path.orEmpty().trim('/').isNotEmpty())
            val key = row.getString("hostKey")
            require(p != StorageProtocol.SFTP || key.isNotBlank())
            if (p == StorageProtocol.WEBDAV) WebDavCertificates.validate(key)
            name to ConnectionConfig(p, uri.toASCIIString(), row.getString("username"), key)
        }
    }
}
