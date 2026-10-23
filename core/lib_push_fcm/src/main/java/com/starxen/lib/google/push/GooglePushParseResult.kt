package com.starxen.lib.google.push

sealed class GooglePushParseResult {
    data class Success(val message: GooglePushMessage) : GooglePushParseResult()
    data class Invalid(val error: GooglePushParseError) : GooglePushParseResult()
}

data class GooglePushParseError(
    val code: GooglePushParseErrorCode,
    val message: String
)

enum class GooglePushParseErrorCode {
    MISSING_EVENT_TYPE
}
