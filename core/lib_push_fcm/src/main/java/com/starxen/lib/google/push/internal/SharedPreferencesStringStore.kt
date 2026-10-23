package com.starxen.lib.google.push.internal

import android.content.Context

internal class SharedPreferencesStringStore(
    context: Context,
    preferencesName: String
) : StringKeyValueStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE
    )

    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}
