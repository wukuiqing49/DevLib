package com.wkq.iptc.upload.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** 协程取消时取消实际网络请求；响应到达时若调用方已取消，立即释放响应体。 */
internal suspend fun Call.awaitUploadResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        /** 网络失败回传原始异常，已取消的协程保留原取消原因。 */
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        /** 响应所有权转交调用方；取消竞争时由恢复回调关闭。 */
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
