package com.starxen.lib.google.auth

data class GoogleAccountInfo(
    val idToken: String,
    val accountId: String?,
    val email: String?,
    val displayName: String?,
    val givenName: String?,
    val familyName: String?,
    val photoUrl: String?
)
