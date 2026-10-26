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
    UNKNOWN,
}

/** 仅检查类型，不拼接可能含密码、地址或 token 的异常消息。 */
fun demoErrorKind(error: Throwable): DemoErrorKind {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    while (current != null && visited.add(current)) {
        when (current) {
            is SocketTimeoutException,
            is TimeoutCancellationException -> return DemoErrorKind.TIMEOUT
            is SSLException -> return DemoErrorKind.CERTIFICATE
            is SecurityException -> return DemoErrorKind.PERMISSION
            is FileNotFoundException -> return DemoErrorKind.NOT_FOUND
            is IllegalArgumentException -> return DemoErrorKind.INPUT
            is IOException -> return DemoErrorKind.NETWORK
        }
        current = current.cause
    }
    return DemoErrorKind.UNKNOWN
}
