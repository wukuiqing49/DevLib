package com.wkq.naslib

/** 保留已有文件名的原始 Unicode；只规范结构和拒绝越界，不重写文件身份。 */
object NasPaths {
    fun normalize(path: String): String {
        require(!path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000')) { "Invalid relative path" }
        val parts = path.split('/').filter(String::isNotEmpty)
        require(parts.none { it == ".." || it == "." }) { "Path traversal is not allowed" }
        return parts.joinToString("/")
    }
    fun join(directory: String, child: String) = normalize(listOf(directory, child).filter(String::isNotEmpty).joinToString("/"))
    fun parent(path: String) = normalize(path).substringBeforeLast('/', "")
    fun name(path: String) = normalize(path).substringAfterLast('/')
}
