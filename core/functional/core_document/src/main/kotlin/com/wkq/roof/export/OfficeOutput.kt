package com.wkq.roof.export

import java.io.File
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 长文档写入的协作式中断检查，不清除调用线程的中断标记。 */
internal fun checkOfficeActive() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Office export interrupted")
}

/** 同目录原子发布；文件系统不支持时明确失败并保留旧文档，不先删除正式文件。 */
internal fun commitOfficeOutput(temporary: File, output: File) {
    checkOfficeActive()
    Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
