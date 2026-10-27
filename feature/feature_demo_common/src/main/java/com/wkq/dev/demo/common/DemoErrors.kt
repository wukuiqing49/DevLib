package com.wkq.dev.demo.common

import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import kotlinx.coroutines.TimeoutCancellationException

/** 只接收演示层自行生成的提示，禁止把底层异常 message 直接传入。 */
class DemoInputException(val displayMessage: String) : IllegalArgumentException()

/** 将底层失败归类为宿主可资源化的提示类型，不携带异常原文。 */
enum class DemoErrorKind {
    INPUT,
    PERMISSION,
    NOT_FOUND,
    TIMEOUT,
    CERTIFICATE,
    NETWORK,
    BUDGET,
    CONFLICT,
    FORMAT,
    CANCELLED,
    IO,
    UNKNOWN,
}

/** 仅检查类型，不拼接可能含密码、地址或 token 的异常消息。 */
fun demoErrorKind(error: Throwable): DemoErrorKind {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    while (current != null && visited.add(current)) {
        when (current) {
            is com.wkq.photo.core.operation.OperationException -> return when (current.code) {
                com.wkq.photo.core.operation.OperationErrorCode.INVALID_INPUT -> DemoErrorKind.INPUT
                com.wkq.photo.core.operation.OperationErrorCode.SOURCE_NOT_FOUND -> DemoErrorKind.NOT_FOUND
                com.wkq.photo.core.operation.OperationErrorCode.BUDGET_EXCEEDED -> DemoErrorKind.BUDGET
                com.wkq.photo.core.operation.OperationErrorCode.OUTPUT_CONFLICT -> DemoErrorKind.CONFLICT
                com.wkq.photo.core.operation.OperationErrorCode.UNSUPPORTED_FORMAT,
                com.wkq.photo.core.operation.OperationErrorCode.ENCODE_FAILED -> DemoErrorKind.FORMAT
                com.wkq.photo.core.operation.OperationErrorCode.CANCELLED -> DemoErrorKind.CANCELLED
                com.wkq.photo.core.operation.OperationErrorCode.IO_ERROR -> DemoErrorKind.IO
                else -> DemoErrorKind.UNKNOWN
            }
            is SocketTimeoutException,
            is TimeoutCancellationException -> return DemoErrorKind.TIMEOUT
            is SSLException -> return DemoErrorKind.CERTIFICATE
            is SecurityException -> return DemoErrorKind.PERMISSION
            is FileNotFoundException -> return DemoErrorKind.NOT_FOUND
            is IllegalArgumentException -> return DemoErrorKind.INPUT
            is java.net.SocketException, is java.net.UnknownHostException -> return DemoErrorKind.NETWORK
            is IOException -> return DemoErrorKind.IO
        }
        current = current.cause
    }
    return DemoErrorKind.UNKNOWN
}
