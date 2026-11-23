package com.wkq.logreport

/** 从系统文本跟踪中提取有界栈帧，排除进程命令、线程名和其他原始头信息。 */
internal object SystemTraceSummary {
    /** 保留方法位置并对原生库路径执行同一脱敏策略。 */
    fun frames(text: String): String = text.lineSequence().map(String::trim)
        .filter { it.startsWith("at ") || it.startsWith("native: #") }
        .take(256).map(LogPrivacy::redact).joinToString("\n")
}
