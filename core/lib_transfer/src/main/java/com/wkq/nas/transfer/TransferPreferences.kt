package com.wkq.nas.transfer

import android.content.Context

class TransferPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("transfer-policy", Context.MODE_PRIVATE)
    var allowMetered: Boolean
        get() = preferences.getBoolean("metered", false)
        set(value) { preferences.edit().putBoolean("metered", value).apply() }
    var verifyContent: Boolean
        get() = preferences.getBoolean("verify-content", false)
        set(value) { preferences.edit().putBoolean("verify-content", value).apply() }
    var parallelQueues: Boolean
        get() = preferences.getBoolean("parallel-queues", false)
        set(value) { preferences.edit().putBoolean("parallel-queues", value).apply() }
}
