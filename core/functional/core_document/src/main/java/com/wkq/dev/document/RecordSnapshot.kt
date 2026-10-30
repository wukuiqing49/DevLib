package com.wkq.dev.document

import com.google.gson.GsonBuilder
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import com.google.gson.JsonDeserializer
import com.wkq.roof.export.checkOfficeActive
import java.io.File

/** 每次导出的独立快照，包含所用模板、资料、签署记录和图片副本，可从目录恢复并重新导出。 */
object RecordSnapshot {
    /** 复制所有输入图片到本批隔离目录，写相对路径元数据；后台调用并逐块响应中断。 */
    internal fun capture(record: ExportRecord, directory: File): ExportRecord {
        val inputs = (record.images.map { it.file } + listOfNotNull(record.company.logo, record.person.avatar, record.template.watermark.image) +
            record.signatures.mapNotNull { it.image }).distinctBy { it.canonicalPath }
        require(inputs.sumOf { it.length() } <= 256L * 1024 * 1024)
        val copied = inputs.mapIndexed { index, source ->
            val target = File(directory, "assets/image-$index.${source.extension.lowercase(java.util.Locale.ROOT)}")
            target.parentFile!!.mkdirs()
            source.inputStream().use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var size = 0L
                while (true) {
                    checkOfficeActive()
                    val n = input.read(buffer); if (n < 0) break
                    size += n; require(size <= 128L * 1024 * 1024)
                    output.write(buffer, 0, n)
                }
            } }
            source.canonicalPath to target
        }.toMap()
        val snapshot = record.copy(
            images = record.images.map { it.copy(file = copied.getValue(it.file.canonicalPath)) },
            company = record.company.copy(logo = record.company.logo?.let { copied.getValue(it.canonicalPath) }),
            person = record.person.copy(avatar = record.person.avatar?.let { copied.getValue(it.canonicalPath) }),
            signatures = record.signatures.map { it.copy(image = it.image?.let { f -> copied.getValue(f.canonicalPath) }) },
            template = record.template.copy(watermark = record.template.watermark.copy(image = record.template.watermark.image?.let { copied.getValue(it.canonicalPath) })),
        )
        val gson = GsonBuilder().setPrettyPrinting().registerTypeAdapter(File::class.java,
            JsonSerializer<File> { file, _, _ -> JsonPrimitive(file.relativeTo(directory).invariantSeparatorsPath) }).create()
        File(directory, "record-snapshot.json").writeText(gson.toJson(snapshot), Charsets.UTF_8)
        return snapshot
    }

    /** 恢复导出目录中的快照，拒绝越界文件路径、超限或损坏配置，不读取任意本地图片。 */
    fun restore(directory: File): ExportRecord {
        val metadata = File(directory, "record-snapshot.json")
        require(metadata.length() in 1..8L * 1024 * 1024)
        val assets = File(directory, "assets").canonicalFile.toPath()
        val gson = GsonBuilder().registerTypeAdapter(File::class.java, JsonDeserializer<File> { json, _, _ ->
            val file = File(directory, json.asString).canonicalFile
            require(file.toPath().startsWith(assets) && file.isFile)
            file
        }).create()
        return gson.fromJson(metadata.readText(Charsets.UTF_8), ExportRecord::class.java).also { it.validate() }
    }
}
