package com.wkq.iptc.feature.press.metadata

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** 仅运行随 APK 分发的解释器及固定元数据命令，不接受用户脚本或配置。 */
internal object NativeMetadataRuntime {
    private val installLock = Mutex()
    // 限制解释器与输出缓冲区的并发数量，等待许可时仍可取消。
    private val processSlots = Semaphore(2)
    private const val ASSET = "metadata-engine/runtime.zip"
    private const val SHA = "962ad002e6bd38ebfac7566ce9323afcba0720b73e6a042d4a39b666efa47062"
    private const val OUTPUT_LIMIT = 16 * 1024 * 1024

    private suspend fun install(context: Context): File = installLock.withLock {
        val root = File(context.filesDir, "metadata_runtime_$SHA")
        if (!File(root, ".ready").isFile) {
            val temp = File(context.filesDir, "metadata_runtime_install")
            if (temp.exists()) check(temp.deleteRecursively())
            check(temp.mkdirs())
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                context.assets.open(ASSET).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) } == SHA) { "Metadata runtime integrity check failed" }
                var total = 0L
                ZipInputStream(context.assets.open(ASSET).buffered()).use { zip ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        val target = File(temp, entry.name).canonicalFile
                        check(target.path.startsWith(temp.canonicalPath + File.separator)) { "Invalid runtime archive path" }
                        if (entry.isDirectory) { check(target.isDirectory || target.mkdirs()); continue }
                        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                        target.outputStream().use { out ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = zip.read(buffer); if (n < 0) break
                                total += n; check(total <= 128L * 1024 * 1024) { "Runtime extraction exceeds limit" }
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                }
                check(File(temp, "exiftool").isFile && File(temp, "xs_manifest.txt").isFile)
                if (root.exists()) check(root.deleteRecursively())
                check(temp.renameTo(root))
                File(root, ".ready").writeText(SHA)
            } finally { temp.deleteRecursively() }
        }
        // 应用升级会改变 nativeLibraryDir，每次安装检查都重新连接 APK 内的 XS 模块。
        File(root, "xs_manifest.txt").forEachLine { line ->
            val parts = line.trim().split(' ')
            check(parts.size == 2)
            val link = File(root, "arch/auto/${parts[0]}").absoluteFile
            check(link.path.startsWith(File(root, "arch/auto").absolutePath + File.separator))
            check(!parts[0].split('/').contains("..") && parts[1].matches(Regex("libperl_xs_[A-Za-z0-9_]+\\.so")))
            val target = File(context.applicationInfo.nativeLibraryDir, parts[1])
            check(target.isFile) { "Metadata native module unavailable" }
            check(link.parentFile!!.isDirectory || link.parentFile!!.mkdirs())
            if (runCatching { Os.readlink(link.path) }.getOrNull() != target.path) {
                runCatching { Os.remove(link.path) }
                Os.symlink(target.path, link.path)
            }
        }
        root
    }

    suspend fun execute(context: Context, arguments: List<String>): ByteArray = processSlots.withPermit { withContext(Dispatchers.IO) {
        val root = install(context.applicationContext)
        val interpreter = File(context.applicationInfo.nativeLibraryDir, "libperl.so")
        check(interpreter.isFile) { "Metadata runtime requires extracted native libraries" }
        MetadataExecutionService.protect(context) { checkRunning ->
            val command = listOf(interpreter.path, "-I", File(root, "arch").path, "-I", File(root, "lib").path,
                File(root, "exiftool").path, "-config", "", "-charset", "filename=UTF8") + arguments
            val builder = ProcessBuilder(command).directory(root).apply {
                environment().remove("PERL5OPT")
                environment().remove("PERL5LIB")
                environment()["LC_ALL"] = "C.UTF-8"
            }
            try {
                MetadataProcessRunner.run(builder, context.cacheDir,
                allowReadWarnings = "-overwrite_original" !in arguments,
                clock = { android.os.SystemClock.elapsedRealtime() },
                checkRunning = checkRunning,
                start = { commandBuilder, output, errors ->
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        commandBuilder.redirectOutput(output).redirectError(errors).start()
                    } else {
                        // API 24/25 没有文件重定向 API。仅把路径和参数作为位置参数传入固定脚本，
                        // 不拼接照片名、元数据或命令；exec 保持子进程 PID，便于正常取消。
                        val original = commandBuilder.command().toList()
                        commandBuilder.command(listOf("/system/bin/sh", "-c",
                            "out=\u00241; err=\u00242; shift 2; exec \"\u0024@\" >\"\u0024out\" 2>\"\u0024err\"",
                            "metadata", output.path, errors.path) + original).start()
                    }
                },
                terminate = { process ->
                    if (android.os.Build.VERSION.SDK_INT >= 26) process.destroyForcibly()
                    else process.destroy()
                })
            } catch (failure: IllegalStateException) {
                // 仅使用固定分类，不输出命令参数、文件路径或元数据正文。
                val operation = when {
                    "-overwrite_original" in arguments -> "WRITE"
                    "-ImageDataHash" in arguments -> "READ_INTEGRITY"
                    "-FileType" in arguments -> "READ_FILE_TYPE"
                    "-b" in arguments -> "READ_BINARY"
                    else -> "READ_METADATA"
                }
                throw IllegalStateException("Metadata engine operation=$operation failed", failure)
            }
        }
    } }
}
