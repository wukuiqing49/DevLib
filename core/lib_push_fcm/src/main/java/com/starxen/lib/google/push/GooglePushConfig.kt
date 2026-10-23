package com.starxen.lib.google.push

data class GooglePushConfig(
    val smallIconResId: Int = 0,
    val channelId: String = DEFAULT_CHANNEL_ID,
    val channelName: String? = null,
    val channelDescription: String? = null,
    val notificationColor: Int? = null,
    val notificationsEnabled: Boolean = true,
    val firebaseConfig: GoogleFirebaseConfig? = null,
    val notificationClickHandler: GooglePushClickHandler? = null
) {
    companion object {
        const val DEFAULT_CHANNEL_ID = "google_push_default"

        @JvmStatic
        @JvmOverloads
        fun create(
            smallIconResId: Int = 0,
            firebaseConfig: GoogleFirebaseConfig? = null,
            notificationClickHandler: GooglePushClickHandler? = null
        ): GooglePushConfig {
            return GooglePushConfig(
                smallIconResId = smallIconResId,
                firebaseConfig = firebaseConfig,
                notificationClickHandler = notificationClickHandler
            )
        }
    }
}
