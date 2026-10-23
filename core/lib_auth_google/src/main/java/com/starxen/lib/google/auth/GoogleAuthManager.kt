package com.starxen.lib.google.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object GoogleAuthManager {
    private val callbacks = ConcurrentHashMap<String, GoogleLoginCallback>()

    @JvmStatic
    fun signIn(
        activity: Activity,
        config: GoogleAuthConfig,
        callback: GoogleLoginCallback
    ) = signIn(activity, config.serverClientId, callback)

    @JvmStatic
    fun signIn(
        activity: Activity,
        serverClientId: String,
        callback: GoogleLoginCallback
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            callback.onResult(
                failure(GoogleLoginErrorCode.ACTIVITY_UNAVAILABLE, "The Activity is unavailable")
            )
            return
        }
        if (serverClientId.isBlank()) {
            callback.onResult(
                failure(GoogleLoginErrorCode.INVALID_CLIENT_ID, "The server client ID is empty")
            )
            return
        }

        val availability = GoogleApiAvailability.getInstance()
        val availabilityCode = availability.isGooglePlayServicesAvailable(activity)
        if (availabilityCode != ConnectionResult.SUCCESS) {
            callback.onResult(
                GoogleLoginResult.Failure(
                    GoogleLoginError(
                        code = GoogleLoginErrorCode.PLAY_SERVICES_UNAVAILABLE,
                        message = "Google Play services unavailable (code=$availabilityCode)",
                        providerStatusCode = availabilityCode,
                        recoverable = availability.isUserResolvableError(availabilityCode)
                    )
                )
            )
            return
        }

        val requestId = UUID.randomUUID().toString()
        callbacks[requestId] = callback
        try {
            activity.startActivity(
                Intent(activity, GoogleAuthProxyActivity::class.java)
                    .putExtra(GoogleAuthProxyActivity.EXTRA_REQUEST_ID, requestId)
                    .putExtra(GoogleAuthProxyActivity.EXTRA_SERVER_CLIENT_ID, serverClientId)
            )
        } catch (error: Exception) {
            callbacks.remove(requestId)
            callback.onResult(
                failure(
                    GoogleLoginErrorCode.INTERNAL,
                    error.message ?: "Unable to start Google sign-in"
                )
            )
        }
    }

    @JvmStatic
    fun signOut(
        context: Context,
        config: GoogleAuthConfig,
        callback: GoogleAccountOperationCallback
    ) = signOut(context, config.serverClientId, callback)

    @JvmStatic
    fun signOut(
        context: Context,
        serverClientId: String,
        callback: GoogleAccountOperationCallback
    ) {
        accountOperation(context, serverClientId, callback) { client -> client.signOut() }
    }

    @JvmStatic
    fun revokeAccess(
        context: Context,
        config: GoogleAuthConfig,
        callback: GoogleAccountOperationCallback
    ) = revokeAccess(context, config.serverClientId, callback)

    @JvmStatic
    fun revokeAccess(
        context: Context,
        serverClientId: String,
        callback: GoogleAccountOperationCallback
    ) {
        accountOperation(context, serverClientId, callback) { client -> client.revokeAccess() }
    }

    internal fun dispatch(requestId: String?, result: GoogleLoginResult) {
        if (requestId.isNullOrBlank()) return
        callbacks.remove(requestId)?.onResult(result)
    }

    internal fun parseSignInResult(intent: Intent?): GoogleLoginResult {
        return try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(intent)
                .getResult(ApiException::class.java)
            account.toLoginResult()
        } catch (error: ApiException) {
            if (error.statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED ||
                error.statusCode == CommonStatusCodes.CANCELED
            ) {
                GoogleLoginResult.Cancelled
            } else {
                GoogleLoginResult.Failure(mapError(error))
            }
        } catch (error: Exception) {
            failure(
                GoogleLoginErrorCode.UNKNOWN,
                error.message ?: "Unknown Google sign-in error"
            )
        }
    }

    internal fun buildOptions(serverClientId: String): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(serverClientId)
            .requestEmail()
            .requestProfile()
            .build()
    }

    private fun GoogleSignInAccount.toLoginResult(): GoogleLoginResult {
        val token = idToken
        if (token.isNullOrBlank()) {
            return failure(
                GoogleLoginErrorCode.TOKEN_MISSING,
                "Google did not return an ID token"
            )
        }
        return GoogleLoginResult.Success(
            GoogleAccountInfo(
                idToken = token,
                accountId = id,
                email = email,
                displayName = displayName,
                givenName = givenName,
                familyName = familyName,
                photoUrl = photoUrl?.toString()
            )
        )
    }

    private fun mapError(error: ApiException): GoogleLoginError {
        val code = when (error.statusCode) {
            CommonStatusCodes.NETWORK_ERROR -> GoogleLoginErrorCode.NETWORK
            GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS ->
                GoogleLoginErrorCode.ALREADY_IN_PROGRESS
            CommonStatusCodes.DEVELOPER_ERROR -> GoogleLoginErrorCode.DEVELOPER_CONFIGURATION
            CommonStatusCodes.INTERNAL_ERROR -> GoogleLoginErrorCode.INTERNAL
            GoogleSignInStatusCodes.SIGN_IN_FAILED -> GoogleLoginErrorCode.AUTHENTICATION_FAILED
            else -> GoogleLoginErrorCode.UNKNOWN
        }
        return GoogleLoginError(
            code = code,
            message = error.message ?: GoogleSignInStatusCodes.getStatusCodeString(error.statusCode),
            providerStatusCode = error.statusCode,
            recoverable = code == GoogleLoginErrorCode.NETWORK
        )
    }

    private fun accountOperation(
        context: Context,
        serverClientId: String,
        callback: GoogleAccountOperationCallback,
        operation: (GoogleSignInClient) -> com.google.android.gms.tasks.Task<Void>
    ) {
        if (serverClientId.isBlank()) {
            callback.onResult(
                GoogleAccountOperationResult.Failure(
                    GoogleLoginError(
                        GoogleLoginErrorCode.INVALID_CLIENT_ID,
                        "The server client ID is empty"
                    )
                )
            )
            return
        }
        try {
            val client = GoogleSignIn.getClient(context, buildOptions(serverClientId))
            operation(client)
                .addOnSuccessListener { callback.onResult(GoogleAccountOperationResult.Success) }
                .addOnFailureListener { error ->
                    callback.onResult(
                        GoogleAccountOperationResult.Failure(
                            GoogleLoginError(
                                GoogleLoginErrorCode.INTERNAL,
                                error.message ?: "Google account operation failed"
                            )
                        )
                    )
                }
        } catch (error: Exception) {
            callback.onResult(
                GoogleAccountOperationResult.Failure(
                    GoogleLoginError(
                        GoogleLoginErrorCode.INTERNAL,
                        error.message ?: "Google account operation failed"
                    )
                )
            )
        }
    }

    private fun failure(code: GoogleLoginErrorCode, message: String): GoogleLoginResult.Failure {
        return GoogleLoginResult.Failure(GoogleLoginError(code, message))
    }
}
