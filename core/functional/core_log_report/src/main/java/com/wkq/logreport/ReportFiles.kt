package com.wkq.logreport

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 报告写入与封存只在IO队列调用，原子替换避免留下半份状态。 */
internal object ReportFiles {
    /** 完整写入后提交同目录文件。 */
    fun atomic(file: File, text: String) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary).use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** 封存失败时保留active标记；重复执行可继续整理同一份报告。 */
    fun seal(folder: File, prepare: () -> Unit) {
        val active = File(folder, "active")
        val finished = File(folder, "finished")
        if (finished.exists() && !active.exists()) return
        check(active.isFile) { "Missing active report" }
        prepare()
        Files.move(active.toPath(), finished.toPath(), StandardCopyOption.ATOMIC_MOVE)
        check(finished.setLastModified(System.currentTimeMillis()))
    }
}
