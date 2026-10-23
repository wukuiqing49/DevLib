package com.wkq.photo.qrcode

import android.graphics.Bitmap
import android.graphics.Color

enum class QRModuleStyle {
    SQUARE,
    ROUNDED_SQUARE,
    CIRCLE_DOT
}

enum class QRFinderPatternStyle {
    SQUARE,
    ROUNDED,
    CIRCLE
}

enum class QRErrorCorrection {
    MEDIUM,
    HIGH
}

sealed interface QRColorStyle {
    val backgroundColor: Int

    data class Solid(
        val foregroundColor: Int = Color.BLACK,
        override val backgroundColor: Int = Color.WHITE
    ) : QRColorStyle

    data class Gradient(
        val startColor: Int,
        val endColor: Int,
        override val backgroundColor: Int = Color.WHITE
    ) : QRColorStyle
}

data class QRCodeRequest(
    val content: String,
    val sizePx: Int = 1024,
    val moduleStyle: QRModuleStyle = QRModuleStyle.SQUARE,
    val finderPatternStyle: QRFinderPatternStyle = QRFinderPatternStyle.SQUARE,
    val colorStyle: QRColorStyle = QRColorStyle.Solid(),
    val logo: Bitmap? = null,
    val backgroundImage: Bitmap? = null,
    val backgroundVeilAlpha: Float = 0.84f,
    val quietZoneModules: Int = 4,
    val errorCorrection: QRErrorCorrection = if (logo != null || backgroundImage != null) {
        QRErrorCorrection.HIGH
    } else {
        QRErrorCorrection.MEDIUM
    },
    val verifyScannable: Boolean = true
)

sealed interface QRPayload {
    fun encode(): String

    data class Text(val value: String) : QRPayload {
        override fun encode(): String = value.also { require(it.isNotBlank()) { "Text content is empty" } }
    }

    data class Url(val value: String) : QRPayload {
        override fun encode(): String {
            val normalized = value.trim()
            val scheme = runCatching { java.net.URI(normalized).scheme?.lowercase() }.getOrNull()
            require(scheme == "http" || scheme == "https") { "URL must use http or https" }
            return normalized
        }
    }

    data class Wifi(
        val ssid: String,
        val password: String = "",
        val security: Security = Security.WPA,
        val hidden: Boolean = false
    ) : QRPayload {
        enum class Security(val encodedName: String) {
            WPA("WPA"),
            WEP("WEP"),
            NONE("nopass")
        }

        override fun encode(): String {
            require(ssid.isNotBlank()) { "Wi-Fi SSID is empty" }
            if (security != Security.NONE) require(password.isNotEmpty()) { "Wi-Fi password is empty" }
            val encodedPassword = if (security == Security.NONE) "" else escapeWifi(password)
            return "WIFI:T:${security.encodedName};S:${escapeWifi(ssid)};P:$encodedPassword;H:$hidden;;"
        }
    }

    data class Contact(
        val name: String,
        val phone: String = "",
        val email: String = "",
        val organization: String = ""
    ) : QRPayload {
        override fun encode(): String {
            require(name.isNotBlank()) { "Contact name is empty" }
            return buildString {
                append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
                append("FN:").append(escapeVCard(name)).append("\r\n")
                if (organization.isNotBlank()) append("ORG:").append(escapeVCard(organization)).append("\r\n")
                if (phone.isNotBlank()) append("TEL:").append(escapeVCard(phone)).append("\r\n")
                if (email.isNotBlank()) append("EMAIL:").append(escapeVCard(email)).append("\r\n")
                append("END:VCARD")
            }
        }
    }

    companion object {
        private fun escapeWifi(value: String): String = buildString(value.length) {
            value.forEach { character ->
                if (character == '\\' || character == ';' || character == ',' || character == ':' || character == '"') {
                    append('\\')
                }
                append(character)
            }
        }

        private fun escapeVCard(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace(";", "\\;")
            .replace(",", "\\,")
    }
}
