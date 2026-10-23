package com.starxen.lib.google.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Public Firebase configuration owned by the host application.
 * This does not include a service-account credential or any server secret.
 */
data class GoogleFirebaseConfig(
    val applicationId: String,
    val apiKey: String,
    val projectId: String,
    val senderId: String
) {
    fun initialize(context: Context): GooglePushOperationResult {
        val missingField = missingFieldName()
        if (missingField != null) {
            return failure(
                GooglePushErrorCode.INVALID_FIREBASE_CONFIG,
                "$missingField must not be blank"
            )
        }

        return synchronized(INITIALIZATION_LOCK) {
            val applicationContext = context.applicationContext
            if (hasDefaultFirebaseApp()) {
                GooglePushOperationResult.Success
            } else {
                try {
                    val options = FirebaseOptions.Builder()
                        .setApplicationId(applicationId)
                        .setApiKey(apiKey)
                        .setProjectId(projectId)
                        .setGcmSenderId(senderId)
                        .build()
                    val firebaseApp = FirebaseApp.initializeApp(applicationContext, options)
                    if (firebaseApp == null) {
                        failure(
                            GooglePushErrorCode.FIREBASE_INITIALIZATION_FAILED,
                            "FirebaseApp could not be initialized"
                        )
                    } else {
                        GooglePushOperationResult.Success
                    }
                } catch (error: Exception) {
                    failure(
                        GooglePushErrorCode.FIREBASE_INITIALIZATION_FAILED,
                        error.message ?: "FirebaseApp could not be initialized"
                    )
                }
            }
        }
    }

    private fun missingFieldName(): String? = when {
        applicationId.isBlank() -> "applicationId"
        apiKey.isBlank() -> "apiKey"
        projectId.isBlank() -> "projectId"
        senderId.isBlank() -> "senderId"
        else -> null
    }

    private fun failure(
        code: GooglePushErrorCode,
        message: String
    ) = GooglePushOperationResult.Failure(GooglePushError(code, message))

    private fun hasDefaultFirebaseApp(): Boolean = try {
        FirebaseApp.getInstance()
        true
    } catch (_: IllegalStateException) {
        false
    }

    private companion object {
        val INITIALIZATION_LOCK = Any()
    }
}
