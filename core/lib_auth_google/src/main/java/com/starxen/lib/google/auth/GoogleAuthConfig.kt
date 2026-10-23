package com.starxen.lib.google.auth

/**
 * Google OAuth configuration owned by the host application.
 * Use the Web OAuth client ID as [serverClientId].
 */
data class GoogleAuthConfig(
    val serverClientId: String
)
