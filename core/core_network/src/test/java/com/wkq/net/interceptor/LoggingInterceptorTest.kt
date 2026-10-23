package com.wkq.net.interceptor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LoggingInterceptorTest {

    @Test
    fun redactsSensitiveFieldsInNestedJson() {
        val method = LoggingInterceptor::class.java.getDeclaredMethod("formatJsonIfNeeded", String::class.java)
            .apply { isAccessible = true }

        val logged = method.invoke(
            LoggingInterceptor,
            """{"token":"top-secret","nested":{"password":"hidden-value"},"items":[{"authorization":"bearer"}]}"""
        ) as String

        assertFalse(logged.contains("top-secret"))
        assertFalse(logged.contains("hidden-value"))
        assertFalse(logged.contains("bearer"))
    }

    @Test
    fun rejectsBaseUrlKeyAsGlobalHeader() {
        assertThrows(IllegalArgumentException::class.java) {
            HeaderInterceptor(mapOf(HeaderInterceptor.HEADER_BASE_URL_KEY to "user"))
        }

        val interceptor = HeaderInterceptor(emptyMap())
        assertThrows(IllegalArgumentException::class.java) {
            interceptor.addHeader(HeaderInterceptor.HEADER_BASE_URL_KEY, "user")
        }
    }
}
