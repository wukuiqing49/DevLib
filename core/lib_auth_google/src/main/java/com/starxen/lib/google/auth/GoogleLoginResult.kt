package com.starxen.lib.google.auth

sealed class GoogleLoginResult {
    data class Success(val account: GoogleAccountInfo) : GoogleLoginResult()
    data class Failure(val error: GoogleLoginError) : GoogleLoginResult()
    object Cancelled : GoogleLoginResult()
}

fun interface GoogleLoginCallback {
    fun onResult(result: GoogleLoginResult)
}

sealed class GoogleAccountOperationResult {
    object Success : GoogleAccountOperationResult()
    data class Failure(val error: GoogleLoginError) : GoogleAccountOperationResult()
}

fun interface GoogleAccountOperationCallback {
    fun onResult(result: GoogleAccountOperationResult)
}
