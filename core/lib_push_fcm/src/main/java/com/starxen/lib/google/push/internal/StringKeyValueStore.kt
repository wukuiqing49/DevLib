package com.starxen.lib.google.push.internal

internal interface StringKeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}
