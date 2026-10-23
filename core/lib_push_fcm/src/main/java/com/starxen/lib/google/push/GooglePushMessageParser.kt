package com.starxen.lib.google.push

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object GooglePushMessageParser {

    @JvmStatic
    @JvmOverloads
    fun parse(
        data: Map<String, String>,
        fallbackMessageId: String? = null,
        fallbackTitle: String? = null,
        fallbackBody: String? = null,
        fallbackSentTimeMillis: Long = 0L,
        fallbackTtlSeconds: Int = 0
    ): GooglePushParseResult {
        val eventType = data[KEY_EVENT_TYPE].orEmpty().ifBlank {
            data[KEY_LEGACY_TYPE].orEmpty()
        }.trim()

        val title = data[KEY_TITLE].orEmpty().ifBlank { fallbackTitle.orEmpty() }
        val body = data[KEY_BODY].orEmpty().ifBlank { fallbackBody.orEmpty() }
        val messageId = data[KEY_MESSAGE_ID].orEmpty().ifBlank {
            fallbackMessageId.orEmpty()
        }.ifBlank {
            fingerprint(eventType, title, body, data)
        }
        val sentTimeMillis = data[KEY_SENT_TIME]?.toLongOrNull() ?: fallbackSentTimeMillis
        val ttlSeconds = data[KEY_TTL]?.toIntOrNull() ?: fallbackTtlSeconds

        return GooglePushParseResult.Success(
            GooglePushMessage(
                messageId = messageId,
                eventType = eventType,
                title = title,
                body = body,
                data = data.toSortedMap(),
                sentTimeMillis = sentTimeMillis,
                ttlSeconds = ttlSeconds
            )
        )
    }

    private fun fingerprint(
        eventType: String,
        title: String,
        body: String,
        data: Map<String, String>
    ): String {
        val source = buildString {
            append(eventType)
            append('|')
            append(title)
            append('|')
            append(body)
            data.toSortedMap().forEach { (key, value) ->
                append('|')
                append(key)
                append('=')
                append(value)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private const val KEY_MESSAGE_ID = "message_id"
    private const val KEY_EVENT_TYPE = "event_type"
    private const val KEY_LEGACY_TYPE = "type"
    private const val KEY_TITLE = "title"
    private const val KEY_BODY = "body"
    private const val KEY_SENT_TIME = "sent_time"
    private const val KEY_TTL = "ttl"
}
