package com.wkq.dev.document

import com.wkq.roof.export.checkOfficeActive
import com.wkq.roof.export.excel.SimpleXlsxWriter
import com.wkq.roof.export.word.SimpleDocxWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 记录导出统一入口；后台线程调用，可通过线程中断取消。整批成功才原子发布独立目录。 */
object RecordExporter {
    /** 导出选定格式并返回文件列表；失败或取消清理临时目录，不修改既有导出。进度回调在调用线程执行。 */
    fun export(
        record: ExportRecord,
        outputDirectory: File,
        formats: Set<RecordFormat> = RecordFormat.entries.toSet(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<File> {
        require(formats.isNotEmpty())
        // 隔离宿主可变集合，三个格式使用完全相同的数据快照。
        var snapshot = record.copy(fields = record.fields.toList(), rows = record.rows.map { it.toList() }, images = record.images.toList(),
            signatures = record.signatures.toList(), template = record.template.copy(blocks = record.template.blocks.toList(),
                labels = record.template.labels.toMap(), fieldLabels = record.template.fieldLabels.toMap(),
                detailColumns = record.template.detailColumns.toList(), fieldNames = record.template.fieldNames.toList()))
        snapshot.validate()
        checkOfficeActive()
        (snapshot.images.map { it.file } + listOfNotNull(snapshot.company.logo, snapshot.person.avatar, snapshot.template.watermark.image) +
            snapshot.signatures.mapNotNull { it.image }).forEach { checkOfficeActive(); RecordPdfWriter.imageBounds(it) }
        check(outputDirectory.isDirectory || outputDirectory.mkdirs())
        val temporary = Files.createTempDirectory(outputDirectory.toPath(), ".record-").toFile()
        val published = File(outputDirectory, "record-${UUID.randomUUID()}")
        try {
            snapshot = RecordSnapshot.capture(snapshot, temporary)
            val watermark = RecordWatermarkRenderer.render(snapshot.template, File(temporary, "watermark.png"))
            val ordered = RecordFormat.entries.filter { it in formats }
            ordered.forEachIndexed { index, format ->
                checkOfficeActive()
                val file = File(temporary, "record.${format.extension}")
                when (format) {
                    RecordFormat.PDF -> RecordPdfWriter.write(file, snapshot, watermark)
                    RecordFormat.EXCEL -> SimpleXlsxWriter.write(file, RecordOfficeMapper.excel(snapshot, watermark),
                        SimpleXlsxWriter.WorkbookTheme(primaryColor = snapshot.template.style.primaryColor,
                            bodySize = snapshot.template.style.bodySize, titleSize = snapshot.template.style.titleSize,
                            headerFill = snapshot.template.style.headerFill, headerTextColor = "1F2937"))
                    RecordFormat.WORD -> SimpleDocxWriter.write(file, RecordOfficeMapper.word(snapshot, watermark))
                }
                onProgress(index + 1, ordered.size)
            }
            checkOfficeActive()
            Files.move(temporary.toPath(), published.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return ordered.map { File(published, "record.${it.extension}") }
        } finally {
            temporary.deleteRecursively()
        }
    }
}
