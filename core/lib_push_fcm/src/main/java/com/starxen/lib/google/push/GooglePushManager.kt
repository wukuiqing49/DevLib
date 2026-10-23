package com.starxen.lib.google.push

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import com.starxen.lib.google.push.internal.GoogleNotificationFactory
import com.starxen.lib.google.push.internal.GooglePushMessageCodec
import com.starxen.lib.google.push.internal.MessageDeduplicator
import com.starxen.lib.google.push.internal.NotificationCreationResult
import com.starxen.lib.google.push.internal.PendingPushStore
import com.starxen.lib.google.push.internal.SharedPreferencesStringStore

object GooglePushManager {
    @Volatile
    private var config = GooglePushConfig()

    @Volatile
    private var listener: GooglePushListener? = null

    @JvmStatic
    @JvmOverloads
    fun initialize(
        context: Context,
        config: GooglePushConfig = GooglePushConfig(),
        listener: GooglePushListener? = null
    ) {
        initializeWithResult(context, config, listener)
    }

    /**
     * Same initialization as [initialize], with a result the host can handle directly.
     */
    @JvmStatic
    @JvmOverloads
    fun initializeWithResult(
        context: Context,
        config: GooglePushConfig = GooglePushConfig(),
        listener: GooglePushListener? = null
    ): GooglePushOperationResult {
        this.config = config
        this.listener = listener
        val firebaseInitialization = config.firebaseConfig?.let {
            initializeFirebase(context, it)
        }
        if (firebaseInitialization is GooglePushOperationResult.Failure) {
            listener?.onError(firebaseInitialization.error)
            return firebaseInitialization
        }
        cachedToken(context)?.takeIf { it.isNotBlank() }?.let { token ->
            listener?.onTokenChanged(token)
        }
        return GooglePushOperationResult.Success
    }

    /**
     * Initializes the default Firebase app from host-provided public configuration.
     * Call this from Application.onCreate before requesting an FCM token.
     */
    @JvmStatic
    fun initializeFirebase(
        context: Context,
        firebaseConfig: GoogleFirebaseConfig
    ): GooglePushOperationResult = firebaseConfig.initialize(context)

    @JvmStatic
    fun setListener(listener: GooglePushListener?) {
        this.listener = listener
    }

    @JvmStatic
    fun getToken(context: Context, callback: GooglePushTokenCallback) {
        if (!isFirebaseInitialized(context)) {
            callback.onResult(
                GooglePushTokenResult.Failure(
                    GooglePushError(
                        GooglePushErrorCode.FIREBASE_NOT_INITIALIZED,
                        "FirebaseApp has not been initialized"
                    )
                )
            )
            return
        }

        try {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (token.isNullOrBlank()) {
                        callback.onResult(
                            GooglePushTokenResult.Failure(
                                GooglePushError(
                                    GooglePushErrorCode.TOKEN_FETCH_FAILED,
                                    "Firebase returned an empty token"
                                )
                            )
                        )
                    } else {
                        saveToken(context, token)
                        callback.onResult(GooglePushTokenResult.Success(token))
                    }
                }
                .addOnFailureListener { error ->
                    callback.onResult(
                        GooglePushTokenResult.Failure(
                            GooglePushError(
                                GooglePushErrorCode.TOKEN_FETCH_FAILED,
                                error.message ?: "Unable to get the FCM token"
                            )
                        )
                    )
                }
        } catch (error: Exception) {
            callback.onResult(
                GooglePushTokenResult.Failure(
                    GooglePushError(
                        GooglePushErrorCode.TOKEN_FETCH_FAILED,
                        error.message ?: "Unable to get the FCM token"
                    )
                )
            )
        }
    }

    @JvmStatic
    fun deleteToken(context: Context, callback: GooglePushOperationCallback) {
        if (!isFirebaseInitialized(context)) {
            callback.onResult(
                GooglePushOperationResult.Failure(
                    GooglePushError(
                        GooglePushErrorCode.FIREBASE_NOT_INITIALIZED,
                        "FirebaseApp has not been initialized"
                    )
                )
            )
            return
        }

        try {
            FirebaseMessaging.getInstance().deleteToken()
                .addOnSuccessListener {
                    tokenStore(context).remove(KEY_FCM_TOKEN)
                    callback.onResult(GooglePushOperationResult.Success)
                }
                .addOnFailureListener { error ->
                    callback.onResult(
                        GooglePushOperationResult.Failure(
                            GooglePushError(
                                GooglePushErrorCode.TOKEN_DELETE_FAILED,
                                error.message ?: "Unable to delete the FCM token"
                            )
                        )
                    )
                }
        } catch (error: Exception) {
            callback.onResult(
                GooglePushOperationResult.Failure(
                    GooglePushError(
                        GooglePushErrorCode.TOKEN_DELETE_FAILED,
                        error.message ?: "Unable to delete the FCM token"
                    )
                )
            )
        }
    }

    @JvmStatic
    fun cachedToken(context: Context): String? = tokenStore(context).get(KEY_FCM_TOKEN)

    @JvmStatic
    fun peekPendingClick(context: Context): GooglePushMessage? = pendingStore(context).peek()

    @JvmStatic
    fun consumePendingClick(context: Context): GooglePushMessage? = pendingStore(context).consume()

    @JvmStatic
    fun clearPendingClicks(context: Context) = pendingStore(context).clear()

    /** Removes notifications posted by Firebase Messaging for the previous account. */
    @JvmStatic
    fun clearNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            manager.cancelAll()
            return
        }
        val channelIds = setOf(
            config.channelId.ifBlank { GooglePushConfig.DEFAULT_CHANNEL_ID },
            FIREBASE_FALLBACK_CHANNEL_ID
        )
        manager.activeNotifications
            .filter { it.notification.channelId in channelIds }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    @JvmStatic
    fun areNotificationsEnabled(context: Context): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    internal fun handleNewToken(context: Context, token: String) {
        if (token.isBlank()) return
        saveToken(context, token)
        listener?.onTokenChanged(token)
    }

    internal fun handleRemoteMessage(context: Context, remoteMessage: RemoteMessage) {
        val parseResult = GooglePushMessageParser.parse(
            data = remoteMessage.data,
            fallbackMessageId = remoteMessage.messageId,
            fallbackTitle = remoteMessage.notification?.title,
            fallbackBody = remoteMessage.notification?.body,
            fallbackSentTimeMillis = remoteMessage.sentTime,
            fallbackTtlSeconds = remoteMessage.ttl
        )
        if (parseResult is GooglePushParseResult.Invalid) {
            listener?.onError(
                GooglePushError(
                    GooglePushErrorCode.INVALID_MESSAGE,
                    parseResult.error.message
                )
            )
            return
        }

        val message = (parseResult as GooglePushParseResult.Success).message
        if (!deduplicator(context).shouldProcess(message.messageId)) return
        listener?.onMessageReceived(message)

        when (val result = GoogleNotificationFactory().show(context, message, config)) {
            NotificationCreationResult.Success -> Unit
            is NotificationCreationResult.Failure -> listener?.onError(result.error)
        }
    }

    internal fun handleNotificationClick(context: Context, message: GooglePushMessage): Boolean {
        listener?.onNotificationClicked(message)
        val handler = config.notificationClickHandler ?: run {
            pendingStore(context).enqueue(message)
            return false
        }
        return try {
            if (handler.onNotificationClick(context, message)) {
                true
            } else {
                pendingStore(context).enqueue(message)
                false
            }
        } catch (error: Exception) {
            listener?.onError(
                GooglePushError(
                    GooglePushErrorCode.NOTIFICATION_CLICK_FAILED,
                    error.message ?: "Unable to handle notification click"
                )
            )
            pendingStore(context).enqueue(message)
            false
        }
    }

    private fun isFirebaseInitialized(context: Context): Boolean {
        return FirebaseApp.getApps(context.applicationContext).isNotEmpty()
    }

    private fun saveToken(context: Context, token: String) {
        tokenStore(context).put(KEY_FCM_TOKEN, token)
    }

    private fun tokenStore(context: Context) = SharedPreferencesStringStore(
        context,
        PREFERENCES_NAME
    )

    private fun pendingStore(context: Context) = PendingPushStore(
        SharedPreferencesStringStore(context, PREFERENCES_NAME),
        GooglePushMessageCodec()
    )

    private fun deduplicator(context: Context) = MessageDeduplicator(
        SharedPreferencesStringStore(context, PREFERENCES_NAME)
    )

    private const val PREFERENCES_NAME = "lib_google_push"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val FIREBASE_FALLBACK_CHANNEL_ID = "fcm_fallback_notification_channel"
}
