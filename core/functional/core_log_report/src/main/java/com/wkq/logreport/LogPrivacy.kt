package com.wkq.logreport

/** 日志自由文本的防御性脱敏；结构化业务字段仍采用源码白名单。 */
internal object LogPrivacy {
    private const val KEYS = "(?:password|passwd|pwd|(?:access[_-]?|refresh[_-]?|id[_-]?|auth[_-]?|session[_-]?)?token|client[_-]?secret|api[_-]?key|secret|authorization|cookie|set-cookie|credential|serial(?:number)?|ssid|bssid)"
    private val jsonSecret = Regex("(?i)(\\\"" + KEYS + "\\\"\\s*:\\s*)(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|[^,}\\]\\r\\n]+)")
    private val header = Regex("(?im)(\\b(?:authorization|cookie|set-cookie)\\s*:\\s*)[^\\r\\n]+")
    private val keyValue = Regex("(?i)(\\b" + KEYS + "\\b\\s*[=:]\\s*)(?:\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'[^']*'|[^,;\\r\\n]+)")
    private val bearer = Regex("(?i)\\bBearer\\s+[^\\s,;\\\"']+")
    private val quotedPath = Regex("\\\"(?:/|[A-Za-z]:[\\\\/])(?:\\\\.|[^\\\"\\\\])*\\\"")
    private val url = Regex("(?i)(?:https?|content|file)://[^\\s\\\"'<>]+")
    private val path = Regex("(?:[A-Za-z]:[\\\\/]|/)[^\\s\\\"'<>]+")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+")
    private val ipv4 = Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?\\b")
    private val mac = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")

    /** 先在有界输入上脱敏再截断，避免截断把凭据引号切开。 */
    fun redact(value: String): String {
        var text = value.take(16 * 1024)
        if (text.contains("\\\"") && Regex("(?i)\\b" + KEYS + "\\b").containsMatchIn(text)) return "[redacted escaped structured log]"
        text = jsonSecret.replace(text) { it.groupValues[1] + "\"[redacted]\"" }
        text = header.replace(text) { it.groupValues[1] + "[redacted]" }
        text = keyValue.replace(text) { it.groupValues[1] + "[redacted]" }
        text = bearer.replace(text, "Bearer [redacted]")
        text = quotedPath.replace(text, "\"[path]\"")
        text = url.replace(text, "[url]")
        text = path.replace(text, "[path]")
        text = email.replace(text, "[email]")
        text = ipv4.replace(text, "[ip]")
        text = mac.replace(text, "[mac]")
        return text.take(2000)
    }
}
