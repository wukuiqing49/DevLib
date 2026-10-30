package com.wkq.iptc.upload.http

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException

/** 只重试无副作用的读取请求；POST/PUT 的提交结果未知时交给宿主核对，不自动重放。 */
class RetryInterceptor(private val maxRetry: Int = 3) : Interceptor {
    init { require(maxRetry in 1..10) { "maxRetry is the total attempt count, in 1..10" } }

    /** 关闭重试响应并检查取消；中断保留线程标记，不当作弱网继续重试。 */
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val safe = request.method in setOf("GET", "HEAD", "OPTIONS") && request.body == null
        val attempts = if (safe) maxRetry else 1
        var failure: IOException? = null
        repeat(attempts) { index ->
            if (chain.call().isCanceled() || Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("Request cancelled")
            }
            if (index > 0) {
                try { Thread.sleep((1000L * index * index).coerceAtMost(5000L)) }
                catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Retry interrupted").apply { initCause(error) }
                }
            }
            try {
                val response = chain.proceed(request)
                if (response.code !in setOf(502, 503, 504) || index == attempts - 1) return response
                response.close()
            } catch (error: IOException) {
                if (error is InterruptedIOException || chain.call().isCanceled() || index == attempts - 1) throw error
                failure = error
            }
        }
        throw failure ?: IOException("Request attempts exhausted")
    }
}
