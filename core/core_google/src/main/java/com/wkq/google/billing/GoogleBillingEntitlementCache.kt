package com.wkq.google.billing

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class CachedBillingEntitlement(
    val accountId: String,
    val checkedAt: Long,
    val entitlement: GoogleBillingEntitlement
) {
    fun usableFor(account: String, now: Long, graceMillis: Long = OFFLINE_GRACE_MS,
                  retainLifetime: Boolean = false): Boolean = account.isNotBlank() &&
        account == accountId && entitlement.isKnown && checkedAt >= 0L && now >= checkedAt &&
        ((retainLifetime && entitlement.hasLifetimeUnlock) || now - checkedAt < graceMillis)

    companion object {
        const val OFFLINE_GRACE_MS = 24L * 60 * 60 * 1000
    }
}

/** 仅保存 Play 查询成功的结果；放入 noBackupFilesDir，防止备份迁移权益。 */
internal object GoogleBillingEntitlementCache {
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "billing_entitlement.json"))

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) { file(context).delete() }

    suspend fun read(context: Context): CachedBillingEntitlement? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = file(context).openRead().use { input ->
                require(input.available() <= 64 * 1024)
                input.readBytes()
            }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val subscriptions = json.getJSONArray("subscriptions").strings()
            val products = json.getJSONArray("products").strings()
            CachedBillingEntitlement(
                json.getString("account"), json.getLong("checkedAt"),
                GoogleBillingEntitlement(
                    state = if (subscriptions.isNotEmpty() || products.isNotEmpty()) GoogleBillingEntitlementState.PRO else GoogleBillingEntitlementState.FREE,
                    hasPendingPurchase = json.optBoolean("pending", false),
                    hasSubscription = subscriptions.isNotEmpty(), hasLifetimeUnlock = products.isNotEmpty(),
                    activeSubscriptionIds = subscriptions, ownedOneTimeProductIds = products,
                    source = "google_play_cache"
                )
            )
        }.getOrNull()
    }

    suspend fun write(context: Context, snapshot: CachedBillingEntitlement) = withContext(Dispatchers.IO) {
        val atomic = file(context)
        val json = JSONObject().put("account", snapshot.accountId).put("checkedAt", snapshot.checkedAt)
            .put("subscriptions", JSONArray(snapshot.entitlement.activeSubscriptionIds))
            .put("products", JSONArray(snapshot.entitlement.ownedOneTimeProductIds))
            .put("pending", snapshot.entitlement.hasPendingPurchase)
        val output = atomic.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
}
