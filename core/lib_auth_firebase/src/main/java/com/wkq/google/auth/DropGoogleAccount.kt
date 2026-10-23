package com.wkq.google.auth

import android.app.Activity
import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/** Token 交给 Firebase 验证；不把本地解码的 claims 当作可信会话。 */
object DropGoogleAccount {
    val currentUser get() = FirebaseAuth.getInstance().currentUser
    suspend fun signIn(activity: Activity, webClientId: String) {
        val account = GoogleSignInCoordinator.signIn(activity, webClientId).getOrThrow()
        FirebaseAuth.getInstance().signInWithCredential(
            GoogleAuthProvider.getCredential(account.idToken, null)).await()
    }
    suspend fun deleteAccount(context: Context) {
        checkNotNull(currentUser).delete().await()
        GoogleSignInCoordinator.signOut(context)
    }
    suspend fun signOut(context: Context) {
        FirebaseAuth.getInstance().signOut()
        GoogleSignInCoordinator.signOut(context)
    }
}
