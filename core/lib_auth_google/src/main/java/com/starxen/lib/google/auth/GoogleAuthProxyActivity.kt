package com.starxen.lib.google.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.google.android.gms.auth.api.signin.GoogleSignIn

class GoogleAuthProxyActivity : Activity() {
    private var requestId: String? = null
    private var resultDispatched = false
    private var signInStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = savedInstanceState?.getString(STATE_REQUEST_ID)
            ?: intent?.getStringExtra(EXTRA_REQUEST_ID)
        signInStarted = savedInstanceState?.getBoolean(STATE_SIGN_IN_STARTED) ?: false

        val serverClientId = intent?.getStringExtra(EXTRA_SERVER_CLIENT_ID).orEmpty()
        if (requestId.isNullOrBlank() || serverClientId.isBlank()) {
            dispatch(
                GoogleLoginResult.Failure(
                    GoogleLoginError(
                        GoogleLoginErrorCode.INVALID_CLIENT_ID,
                        "Google sign-in configuration is incomplete"
                    )
                )
            )
            return
        }

        if (!signInStarted) {
            try {
                val client = GoogleSignIn.getClient(
                    this,
                    GoogleAuthManager.buildOptions(serverClientId)
                )
                signInStarted = true
                @Suppress("DEPRECATION")
                startActivityForResult(client.signInIntent, REQUEST_GOOGLE_SIGN_IN)
            } catch (error: Exception) {
                dispatch(
                    GoogleLoginResult.Failure(
                        GoogleLoginError(
                            GoogleLoginErrorCode.INTERNAL,
                            error.message ?: "Unable to start Google sign-in"
                        )
                    )
                )
            }
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_GOOGLE_SIGN_IN) {
            dispatch(GoogleAuthManager.parseSignInResult(data))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_REQUEST_ID, requestId)
        outState.putBoolean(STATE_SIGN_IN_STARTED, signInStarted)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing && !resultDispatched) {
            GoogleAuthManager.dispatch(requestId, GoogleLoginResult.Cancelled)
        }
        super.onDestroy()
    }

    private fun dispatch(result: GoogleLoginResult) {
        if (resultDispatched) return
        resultDispatched = true
        GoogleAuthManager.dispatch(requestId, result)
        finish()
    }

    companion object {
        internal const val EXTRA_REQUEST_ID = "lib_google_auth_request_id"
        internal const val EXTRA_SERVER_CLIENT_ID = "lib_google_server_client_id"

        private const val REQUEST_GOOGLE_SIGN_IN = 41001
        private const val STATE_REQUEST_ID = "state_request_id"
        private const val STATE_SIGN_IN_STARTED = "state_sign_in_started"
    }
}
