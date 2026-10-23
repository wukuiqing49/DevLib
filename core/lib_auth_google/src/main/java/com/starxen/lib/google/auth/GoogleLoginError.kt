package com.starxen.lib.google.auth

data class GoogleLoginError(
    val code: GoogleLoginErrorCode,
    val message: String,
    val providerStatusCode: Int? = null,
    val recoverable: Boolean = false
)

enum class GoogleLoginErrorCode {
    INVALID_CLIENT_ID,
    PLAY_SERVICES_UNAVAILABLE,
    NETWORK,
    ALREADY_IN_PROGRESS,
    DEVELOPER_CONFIGURATION,
    TOKEN_MISSING,
    ACTIVITY_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    INTERNAL,
    UNKNOWN
}
