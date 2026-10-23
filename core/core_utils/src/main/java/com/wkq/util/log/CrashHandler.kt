package com.wkq.util.log

import java.io.PrintWriter
import java.io.StringWriter

/**
 * ALog 使用的可选全局崩溃处理器。
 *
 * 发生未捕获异常时先同步写入日志并关闭日志文件，再交给原有处理器继续处理，
 * 因此不会改变宿主应用原本的崩溃行为。
 */
internal class CrashHandler : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        ALog.e("Crash", buildCrashText(t, e))
        ALog.flushSync()
        ALog.closeSync()

        defaultHandler?.uncaughtException(t, e)
    }

    private fun buildCrashText(thread: Thread, throwable: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { printWriter ->
            printWriter.println("Uncaught exception in thread: ${thread.name}")
            throwable.printStackTrace(printWriter)
        }
        return writer.toString()
    }
}
