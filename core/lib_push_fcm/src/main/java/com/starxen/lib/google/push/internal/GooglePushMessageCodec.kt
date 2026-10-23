package com.starxen.lib.google.push.internal

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.starxen.lib.google.push.GooglePushMessage

internal class GooglePushMessageCodec(
    private val gson: Gson = Gson()
) {
    private val listType = object : TypeToken<List<GooglePushMessage>>() {}.type

    fun encode(message: GooglePushMessage): String = gson.toJson(message)

    fun decode(raw: String?): GooglePushMessage? {
        if (raw.isNullOrBlank()) return null
        return runCatching { gson.fromJson(raw, GooglePushMessage::class.java) }.getOrNull()
    }

    fun encodeList(messages: List<GooglePushMessage>): String = gson.toJson(messages, listType)

    fun decodeList(raw: String?): MutableList<GooglePushMessage> {
        if (raw.isNullOrBlank()) return mutableListOf()
        return runCatching {
            gson.fromJson<List<GooglePushMessage>>(raw, listType)?.toMutableList()
                ?: mutableListOf()
        }.getOrDefault(mutableListOf())
    }
}
