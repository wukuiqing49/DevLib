package com.wkq.util.jump

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * URL 处理工具类
 */
object UrlUtils {

    /**
     * 获取重定向后的最终 URL
     * 
     * @param urlString 原始 URL
     * @param maxRedirects 最大重定向次数，防止无限循环
     * @return 最终的 URL
     */
    suspend fun getFinalUrl(urlString: String, maxRedirects: Int = 10): String = withContext(Dispatchers.IO) {
        var currentUrl = urlString
        var redirects = 0
        
        try {
            while (redirects < maxRedirects) {
                val url = URL(currentUrl)
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    // 模拟浏览器 User-Agent，防止某些网站屏蔽爬虫
                    connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)

                    val responseCode = connection.responseCode

                    // 检查是否为重定向响应 (3xx)
                    if (responseCode in 300..399) {
                        val location = connection.getHeaderField("Location")
                        if (location != null) {
                            currentUrl = URL(url, location).toString()
                            redirects++
                            continue
                        }
                    }

                    // 正常响应 (200) 或非重定向响应，返回当前 URL
                    return@withContext currentUrl
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return@withContext currentUrl
    }

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
}
