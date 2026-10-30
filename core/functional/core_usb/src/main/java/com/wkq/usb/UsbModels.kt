package com.wkq.usb

import java.io.IOException

data class UsbEntry(val path: String, val directory: Boolean, val size: Long, val modifiedAt: Long)

enum class UsbError { PERMISSION, NOT_FOUND, CONFLICT, SOURCE_CHANGED, STORAGE_UNAVAILABLE }

class UsbException(val error: UsbError) : IOException(error.name)

internal object UsbPaths {

    val ILLEGAL_CHARS: Set<Char> = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    fun normalize(path: String): String {

        require(!path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000')) { "Invalid relative path" }

        val parts = path.split('/').filter(String::isNotEmpty)

        require(parts.none { it == ".." || it == "." }) { "Path traversal is not allowed" }

        return parts.joinToString("/")

    }

    fun join(directory: String, child: String) = normalize(listOf(directory, child).filter(String::isNotEmpty).joinToString("/"))

    fun parent(path: String) = normalize(path).substringBeforeLast('/', "")

    fun name(path: String) = normalize(path).substringAfterLast('/')

    fun isCrossPlatformSafe(name: String): Boolean {

        val nfc = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)

        if (nfc.isBlank() || nfc.startsWith(' ') || nfc.endsWith(' ') || nfc.endsWith('.')) return false

        return nfc.none { it in ILLEGAL_CHARS || it.code in 0..31 }

    }

    fun sanitizeCrossPlatformName(name: String, replacement: String = "_"): String {

        val nfc = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)

        val sb = StringBuilder()

        for (ch in nfc) {

            if (ch in ILLEGAL_CHARS || ch.code in 0..31) {

                sb.append(replacement)

            } else {

                sb.append(ch)

            }

        }

        var result = sb.toString().trim()

        while (result.endsWith('.')) {

            result = result.dropLast(1).trimEnd()

        }

        return result.ifEmpty { "file" }

    }

}
