package com.starxen.lib.google.push

data class GooglePushMessage(
    val messageId: String,
    val eventType: String,
    val title: String,
    val body: String,
    val data: Map<String, String>,
    val sentTimeMillis: Long = 0L,
    val ttlSeconds: Int = 0
)
