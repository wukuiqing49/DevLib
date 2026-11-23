package com.wkq.logreport

/** 崩溃摘要保留异常原因链与栈帧，排除未经筛选的异常消息。 */
internal object CrashStack {
    /** 限制原因链深度并阻止循环引用，保留方法及行号。 */
    fun render(failure: Throwable): String {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = failure
        return buildString {
            while (cause != null && seen.size < 4 && seen.add(cause)) {
                appendLine(if (seen.size == 1) cause.javaClass.name else "Caused by " + cause.javaClass.name)
                cause.stackTrace.take(48).forEach { appendLine(it.className + "." + it.methodName + ":" + it.lineNumber) }
                cause = cause.cause
            }
        }
    }
}
