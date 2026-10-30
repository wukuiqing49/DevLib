package com.wkq.util

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * 文件及 URI 处理工具类
 */
object FileUtil {
    private var fileProviderAuthority: String? = null

    /**
     * 设置 FileProvider authority。默认值为 ${applicationId}.fileprovider。
     *
     * 宿主 App 如果自定义了 provider authority，应在 CoreUtils.init 或此处显式传入。
     */
    fun setFileProviderAuthority(authority: String?) {
        fileProviderAuthority = authority?.takeIf { it.isNotBlank() }
    }

    /** 获取当前用于 FileProvider 的 authority。 */
    fun getFileProviderAuthority(context: Context): String {
        return fileProviderAuthority ?: "${context.packageName}.fileprovider"
    }

    /**
     * 将本地 [File] 转换为安全的 [Uri]，用于跨应用分享（如相机、相册）
     * 在 Android 7.0 (API 24+) 以上自动使用 FileProvider。
     * 注意：须在 AndroidManifest 注册 ${applicationId}.fileprovider
     *
     * @param context Application 上下文
     * @param file 目标文件
     * @return content:// URI (API 24+) 或 file:// URI (API < 24)
     */
    fun getUriForFile(context: Context, file: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val authority = getFileProviderAuthority(context)
            FileProvider.getUriForFile(context, authority, file)
        } else {
            Uri.fromFile(file)
        }
    }

    /**
     * 将字符串保存到本地文件中
     */
    fun saveStringToFile(content: String, file: File): Boolean {
        return try {
            file.parentFile?.let {
                if (!it.exists()) it.mkdirs()
            }
            FileOutputStream(file).use { fos ->
                fos.write(content.toByteArray())
            }
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 从本地文件中读取字符串
     */
    fun readStringFromFile(file: File): String? {
        if (!file.exists()) return null
        return try {
            FileInputStream(file).use { fis ->
                fis.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    fun writeBytes(file: File, bytes: ByteArray): Boolean {
        return try {
            ensureParentDir(file)
            FileOutputStream(file).use { it.write(bytes) }
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    /** 读取文件字节，文件不存在或读取失败返回 null。 */
    fun readBytes(file: File): ByteArray? {
        if (!file.exists() || !file.isFile) return null
        return try {
            FileInputStream(file).use { it.readBytes() }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    /** 确保目录存在。已存在且是目录时返回 true。 */
    fun ensureDir(dir: File): Boolean {
        return dir.exists() && dir.isDirectory || dir.mkdirs()
    }

    /** 确保文件的父目录存在。 */
    fun ensureParentDir(file: File): Boolean {
        return file.parentFile?.let { ensureDir(it) } ?: true
    }

    /** 创建空文件，父目录不存在时会自动创建。 */
    fun createFile(file: File, overwrite: Boolean = false): Boolean {
        if (file.exists()) {
            if (!overwrite) return file.isFile
            if (!file.delete()) return false
        }
        return try {
            ensureParentDir(file) && file.createNewFile()
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    /** 复制文件。overwrite=false 且目标存在时返回 false。 */
    fun copyFile(source: File, target: File, overwrite: Boolean = true): Boolean {
        if (!source.exists() || !source.isFile) return false
        if (target.exists() && !overwrite) return false
        return try {
            ensureParentDir(target)
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    /** 移动文件。跨存储卷时会退化为复制后删除源文件。 */
    fun moveFile(source: File, target: File, overwrite: Boolean = true): Boolean {
        if (!source.exists() || !source.isFile) return false
        if (target.exists() && !overwrite) return false
        ensureParentDir(target)
        if (source.renameTo(target)) return true
        return copyFile(source, target, overwrite) && delete(source)
    }

    /** 递归删除文件或目录。 */
    fun delete(file: File?): Boolean {
        if (file == null || !file.exists()) return true
        return if (file.isDirectory) {
            file.listFiles()?.forEach { child -> delete(child) }
            file.delete()
        } else {
            file.delete()
        }
    }

    /** 递归计算文件或目录大小。 */
    fun getFileSize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { getFileSize(it) } ?: 0L
    }

    /** 列出目录下的文件，默认不递归。 */
    fun listFiles(dir: File?, recursive: Boolean = false): List<File> {
        if (dir == null || !dir.exists() || !dir.isDirectory) return emptyList()
        val children = dir.listFiles().orEmpty()
        if (!recursive) return children.toList()
        return children.flatMap { child ->
            if (child.isDirectory) listOf(child) + listFiles(child, recursive = true) else listOf(child)
        }
    }

    /** 获取文件扩展名，不包含点号，并统一转为小写。 */
    fun getExtension(fileName: String?): String {
        if (fileName.isNullOrBlank()) return ""
        val index = fileName.lastIndexOf('.')
        return if (index >= 0 && index < fileName.lastIndex) {
            fileName.substring(index + 1).lowercase(Locale.US)
        } else {
            ""
        }
    }

    /** 获取路径中的文件名。 */
    fun getFileName(path: String?): String {
        if (path.isNullOrBlank()) return ""
        return File(path).name
    }

    /** 获取路径中的文件名，不包含扩展名。 */
    fun getFileNameWithoutExtension(path: String?): String {
        if (path.isNullOrBlank()) return ""
        return File(path).nameWithoutExtension
    }

    /** 根据文件名推断常见 MIME 类型。未知类型返回 application/octet-stream。 */
    fun getMimeType(fileName: String?): String {
        return when (getExtension(fileName)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    /** 计算文件 MD5。文件不存在或读取失败返回 null。 */
    fun md5(file: File): String? = digest(file, "MD5")

    /** 计算文件 SHA-256。文件不存在或读取失败返回 null。 */
    fun sha256(file: File): String? = digest(file, "SHA-256")

    /**
     * 将 Uri 转换为本地 File 文件
     * 适配 Android 10+ 作用域存储，通过将内容流复制到指定目录实现
     * 
     * @param destPath 可选，指定存储文件夹路径。若为 null，默认存储在内部存储的 picker 目录下。
     */
    fun uriToFile(context: Context, uri: Uri, destPath: String? = null): File? {
        return when (uri.scheme) {
            "file" -> uri.path?.let { File(it) }?.takeIf { it.exists() }
            "content" -> {
                val contentResolver = context.contentResolver
                val displayName = queryDisplayName(context, uri) ?: "temp_${System.currentTimeMillis()}"
                val targetDir = if (!destPath.isNullOrEmpty()) {
                    File(destPath)
                } else {
                    File(context.cacheDir, "picker")
                }
                if (!targetDir.exists()) targetDir.mkdirs()
                
                val tempFile = File(targetDir, displayName)
                try {
                    val resolvedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && 
                        uri.authority == android.provider.MediaStore.AUTHORITY) {
                        try {
                            android.provider.MediaStore.setRequireOriginal(uri)
                        } catch (e: Exception) {
                            uri
                        }
                    } else {
                        uri
                    }
                    val streamResult = runCatching {
                        contentResolver.openInputStream(resolvedUri)
                    }.recoverCatching {
                        contentResolver.openInputStream(uri)
                    }.getOrNull()

                    streamResult?.use { inputStream ->
                        FileOutputStream(tempFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } ?: error("Failed to open input stream")
                    tempFile
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            else -> null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                    if (idx != -1) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun digest(file: File, algorithm: String): String? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val digest = MessageDigest.getInstance(algorithm)
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
