package com.wkq.iptc.upload

object UploadLogSanitizer {

    fun profile(profile: UploadServerProfile): String {
        return "id=${redactedId(profile.id)}, " +
            "name=${redactedName(profile.name)}, " +
            "protocol=${profile.protocol.name}, " +
            "host=${redactedHost(profile.config.host)}, " +
            "port=${profile.config.port}, " +
            "remoteDir=${redactedPath(profile.config.remoteDir)}, " +
            "user=${redactedUser(profile.config.username)}"
    }

    fun endpoint(config: UploadServerConfig): String {
        return "host=${redactedHost(config.host)}, " +
            "port=${config.port}, " +
            "remoteDir=${redactedPath(config.remoteDir)}"
    }

    fun redactedHost(host: String): String {
        if (host.isBlank()) return "(empty)"
        return "<host:${host.stableHash()}>"
    }

    fun redactedPath(path: String): String {
        if (path.isBlank()) return "(empty)"
        return "<path:${path.stableHash()}>"
    }

    fun redactedUrl(url: String): String {
        if (url.isBlank()) return "(empty)"
        return "<url:${url.stableHash()}>"
    }

    fun redactedUser(user: String): String {
        if (user.isBlank()) return "(empty)"
        return "<user:${user.stableHash()}>"
    }

    fun redactedId(id: String): String {
        return if (id.isBlank()) "(empty)" else "<id:${id.stableHash()}>"
    }

    fun redactedName(name: String): String {
        return if (name.isBlank()) "(empty)" else "<name:${name.stableHash()}>"
    }

    fun throwable(throwable: Throwable): String {
        return "type=${throwable.javaClass.name}, message=${throwable.message.orEmpty().redactSensitiveValues()}"
    }

    fun message(message: String): String {
        return message.redactSensitiveValues()
    }

    private fun String.stableHash(): String {
        return hashCode().toUInt().toString(16).padStart(8, '0')
    }

    private fun String.redactSensitiveValues(): String {
        return replace(Regex("""(?i)\b(?:https?|ftps?|sftp|smb)://[^\s,)]*"""), "<url:redacted>")
            .replace(Regex("""(?i)(host=)[^,\s)]+"""), "$1<redacted>")
            .replace(Regex("""(?i)(remoteDir=)[^,\s)]+"""), "$1<redacted>")
            .replace(Regex("""(?i)(remotePath=)[^,\s)]+"""), "$1<redacted>")
            .replace(Regex("""(?i)(path=)[^,\s)]+"""), "$1<redacted>")
            .replace(Regex("""(?i)(url=)[^,\s)]+"""), "$1<redacted>")
            .replace(Regex("""(?i)(from=)[^,\s)]+"""), "$1<redacted>")
            .replace(Regex("""(?i)(to=)[^,\s)]+"""), "$1<redacted>")
            .replace(Regex("""(?i)(user=)[^,\s)]+"""), "$1<redacted>")
            .replace(Regex("""(?i)(?:password|passwd|pwd|token|authorization|cookie)=[^,\s)]+"""), "<secret:redacted>")
            .replace(Regex("""(?i)\bbasic\s+[a-z0-9+/=]+"""), "Basic <secret:redacted>")
    }
}
