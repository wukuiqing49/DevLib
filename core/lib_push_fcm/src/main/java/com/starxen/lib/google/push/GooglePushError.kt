package com.starxen.lib.google.push

data class GooglePushError(
    val code: GooglePushErrorCode,
    val message: String
)

enum class GooglePushErrorCode {
    INVALID_FIREBASE_CONFIG,
    FIREBASE_INITIALIZATION_FAILED,
    FIREBASE_NOT_INITIALIZED,
    TOKEN_FETCH_FAILED,
    TOKEN_DELETE_FAILED,
    INVALID_MESSAGE,
    NOTIFICATION_PERMISSION_DENIED,
    NOTIFICATION_FAILED,
    NOTIFICATION_CLICK_FAILED,
    UNKNOWN
}

sealed class GooglePushTokenResult {
    data class Success(val token: String) : GooglePushTokenResult()
    data class Failure(val error: GooglePushError) : GooglePushTokenResult()
}

fun interface GooglePushTokenCallback {
    fun onResult(result: GooglePushTokenResult)
}

sealed class GooglePushOperationResult {
    object Success : GooglePushOperationResult()
    data class Failure(val error: GooglePushError) : GooglePushOperationResult()
}

fun interface GooglePushOperationCallback {
    fun onResult(result: GooglePushOperationResult)
}
