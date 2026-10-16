package com.openfit.googlehealth

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface GoogleHealthAuthorization {
    data class Authorized(val accessToken: String) : GoogleHealthAuthorization
    data class NeedsConsent(val consent: PendingIntent) : GoogleHealthAuthorization
}

class GoogleHealthAuth(private val context: Context) {

    private val prefs = context.getSharedPreferences("google_health", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    suspend fun authorize(): GoogleHealthAuthorization {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES.map(::Scope))
            .build()
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        val consent = result.pendingIntent
        return if (result.hasResolution() && consent != null) {
            GoogleHealthAuthorization.NeedsConsent(consent)
        } else {
            GoogleHealthAuthorization.Authorized(checkNotNull(result.accessToken))
        }
    }

    private companion object {
        const val KEY_ENABLED = "enabled"

        val SCOPES = listOf(
            "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly",
            "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly",
            "https://www.googleapis.com/auth/googlehealth.sleep.readonly",
            // Food and water logs, for the history copy.
            "https://www.googleapis.com/auth/googlehealth.nutrition.readonly",
        )
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
}
