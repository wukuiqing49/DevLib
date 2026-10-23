package com.wkq.nas.transfer

import android.content.Context
import androidx.room.withTransaction
import com.wkq.nas.transfer.data.TransferDatabase
import com.wkq.nas.transfer.data.V2Record
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.UUID

enum class StorageKind { NAS, PHONE, USB, SD, AUTHORIZED }

/** 设备分组仅管理展示信息，连接、凭据和授权目录仍保持独立身份。 */
data class StorageLocation(val connectionId: String, val groupId: String, val name: String, val kind: StorageKind)

class StorageLocations(context: Context) {
    private val db = TransferDatabase.get(context.applicationContext)
    private val dao = db.dao()
    fun observe() = dao.observeV2("location").map { rows -> rows.associate { row ->
        val json = JSONObject(row.payload)
        row.id to StorageLocation(row.id, json.getString("group"), json.getString("name"), StorageKind.valueOf(json.getString("kind")))
    } }

    suspend fun assign(connectionId: String, name: String, kind: StorageKind, groupId: String = UUID.randomUUID().toString()) = db.withTransaction {
        require(name.isNotBlank() && name.length <= 100 && groupId.isNotBlank())
        val connection = dao.connection(connectionId) ?: throw TransferException(TransferError.NOT_FOUND)
        val protocol = StorageProtocol.valueOf(connection.protocol)
        require(if (protocol in setOf(StorageProtocol.SMB, StorageProtocol.WEBDAV, StorageProtocol.SFTP)) kind == StorageKind.NAS
            else if (protocol == StorageProtocol.LOCAL) kind == StorageKind.PHONE else kind != StorageKind.NAS)
        // 组名修改同时更新同组位置；禁止 NAS 与外置目录混成同一设备。
        dao.v2Records("location").filter { JSONObject(it.payload).getString("group") == groupId }.forEach { row ->
            val old = JSONObject(row.payload)
            require(old.getString("kind") == kind.name)
            dao.saveV2(row.copy(payload = old.put("name", name.trim()).toString()))
        }
        dao.saveV2(V2Record("location", connectionId, JSONObject().put("group", groupId).put("name", name.trim()).put("kind", kind.name).toString()))
    }
    suspend fun remove(connectionId: String) = dao.deleteV2("location", connectionId)
}
