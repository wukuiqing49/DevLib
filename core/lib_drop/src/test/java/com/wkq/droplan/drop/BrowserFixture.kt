package com.wkq.droplan.drop

import java.io.File
import java.io.InputStream

/** 桌面浏览器回归夹具：仅监听回环、合成数据、自动批准，不用于应用运行。 */
object BrowserFixture {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0])
        val source = object : ShareSource {
            override val id = "fixture_file_0123456789"
            override val displayName = "测试 <img src=x onerror=alert(1)>.bin"
            override val mimeType = "application/octet-stream"
            override val length = 16L * 1024 * 1024
            override val supportsRandomAccess = true
            override fun openStream() = openRange(0, length)
            override fun openRange(offset: Long, length: Long) = object : InputStream() {
                var position = offset
                var remaining = length
                override fun read(): Int = if (remaining-- > 0) (position++ % 251).toInt() else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    if (remaining <= 0) return -1
                    val count = minOf(remaining, len.toLong()).toInt()
                    for (index in 0 until count) b[off + index] = (position++ % 251).toByte()
                    remaining -= count; return count
                }
            }
        }
        DropServer("127.0.0.1", 0, ShareEngine(listOf(source), autoApprove = true), StaticAssetProvider { path ->
            val name = when (path) {
                "/", "/index.html" -> "index.html"
                "/assets/app.css" -> "app.css"
                "/assets/app.js" -> "app.js"
                "/assets/zh.json" -> "zh.json"
                "/assets/en.json" -> "en.json"
                "/assets/ja.json" -> "ja.json"
                "/assets/ko.json" -> "ko.json"
                "/assets/de.json" -> "de.json"
                "/assets/fr.json" -> "fr.json"
                "/assets/es.json" -> "es.json"
                "/assets/pt-BR.json" -> "pt-BR.json"
                else -> null
            }
            name?.let {
                val file = File(root, "core/lib_html/src/main/assets/droplan_web/$it")
                val mime = when (file.extension) { "html" -> "text/html"; "css" -> "text/css"; "js" -> "application/javascript"; else -> "application/json" }
                StaticAsset("$mime; charset=utf-8", file.length()) { file.inputStream() }
            }
        }, preferredTransport = args.getOrElse(1) { "HTTP" }).use { server ->
            server.start()
            File(root, ".ai-work/browser-entry.txt").writeText(server.entryUrl)
            println("Browser fixture ready (loopback only)")
            val stop = File(root, ".ai-work/browser-fixture.stop")
            while (!stop.exists()) Thread.sleep(250)
        }
    }
}
