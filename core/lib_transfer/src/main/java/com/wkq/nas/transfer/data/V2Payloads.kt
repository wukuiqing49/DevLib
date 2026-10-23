package com.wkq.nas.transfer.data

import com.wkq.nas.transfer.TransferError
import com.wkq.nas.transfer.TransferException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

/** 与主记录同事务保存的分块载荷；不改Room结构，不依赖外部缓存文件。 */
internal object V2Payloads {
    const val MUTATION_MARKER = "\u0001nas-mutation-plan-v1"
    private const val PREFIX = "\u0001nas-payload-v1:"
    private const val BLOCK = 32768
    private const val MAX_BLOCKS = 8192
    fun chunkKind(kind: String, id: String) = "\u0001chunks:${kind.length}:$kind$id"

    suspend fun save(dao: TransferDao, record: V2Record) {
        require(!record.kind.startsWith('\u0001'))
        val chunkKind = chunkKind(record.kind, record.id)
        dao.deleteV2Kind(chunkKind)
        // 前缀也转义存入分块，避免自由文本与清单标记碰撞。
        if (record.payload.length <= BLOCK && !record.payload.startsWith(PREFIX)) {
            dao.saveV2Raw(record)
            return
        }
        var start = 0
        var count = 0
        val job = currentCoroutineContext()
        while (start < record.payload.length) {
            job.ensureActive()
            var end = minOf(start + BLOCK, record.payload.length)
            if (end < record.payload.length && record.payload[end - 1].isHighSurrogate() && record.payload[end].isLowSurrogate()) end--
            require(count < MAX_BLOCKS)
            dao.saveV2Raw(V2Record(chunkKind, (count++).toString().padStart(6, '0'), record.payload.substring(start, end)))
            start = end
        }
        dao.saveV2Raw(record.copy(payload = "$PREFIX$count:${record.payload.length}:${digest(record.payload)}"))
    }

    suspend fun expand(dao: TransferDao, head: V2RecordHead): V2Record {
        val value = if (head.payload.startsWith(PREFIX)) {
            val parts = head.payload.removePrefix(PREFIX).split(':')
            val count = parts.getOrNull(0)?.toIntOrNull() ?: corrupt()
            val length = parts.getOrNull(1)?.toIntOrNull() ?: corrupt()
            if (parts.size != 3 || count !in 1..MAX_BLOCKS || length !in 1..MAX_BLOCKS * BLOCK) corrupt()
            val chunks = dao.v2Heads(chunkKind(head.kind, head.id))
            if (chunks.size != count) corrupt()
            val content = StringBuilder()
            chunks.forEachIndexed { index, chunk ->
                currentCoroutineContext().ensureActive()
                if (chunk.id != index.toString().padStart(6, '0') || chunk.payload.length > BLOCK ||
                    chunk.characters != chunk.payload.codePointCount(0, chunk.payload.length)) corrupt()
                content.append(chunk.payload)
            }
            content.toString().also { if (it.length != length || digest(it) != parts[2]) corrupt() }
        } else {
            // SQLite substr/length按Unicode码点计数，不能用UTF-16长度推进旧记录偏移。
            legacy(head.payload, head.characters) { dao.v2Slice(head.kind, head.id, it) }
        }
        return V2Record(head.kind, head.id, value)
    }

    suspend fun mutation(dao: TransferDao, head: MutationJobHead): MutationJobEntity {
        val payload = if (head.payload == MUTATION_MARKER)
            dao.v2Record("mutation-plan", head.id)?.payload ?: corrupt()
        else legacy(head.payload, head.characters) { dao.mutationSlice(head.id, it) }
        return MutationJobEntity(head.id, payload, head.state, head.error, head.connectionId)
    }

    private suspend fun legacy(prefix: String, characters: Int, slice: suspend (Int) -> String?): String {
        val content = StringBuilder(prefix)
        var points = prefix.codePointCount(0, prefix.length)
        while (points < characters) {
            currentCoroutineContext().ensureActive()
            val part = slice(points + 1) ?: corrupt()
            val read = part.codePointCount(0, part.length)
            if (read == 0) corrupt()
            content.append(part)
            points += read
        }
        if (points != characters) corrupt()
        return content.toString()
    }

    private fun digest(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        OutputStreamWriter(DigestOutputStream(sink, digest), Charsets.UTF_8).use { it.write(text) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun corrupt(): Nothing = throw TransferException(TransferError.VERIFICATION)
}
