@file:OptIn(ExperimentalCoroutinesApi::class)

package com.sosina.terefe.budgetingapp.data.sync

import android.util.Log
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.sosina.terefe.budgetingapp.data.auth.AuthRepository
import com.sosina.terefe.budgetingapp.data.remote.UserPaths
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.domain.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "SettingsSync"

/**
 * Keeps the phone's settings and the account's settings (users/{uid}.settings) in step.
 * Does nothing for guests.
 */
@Singleton
class SettingsSyncManager @Inject constructor(
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository
) {

    /** Starts syncing. Call once, from the app's long-lived scope. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            // collectLatest: when the account changes, stop syncing the old one first.
            authRepository.uid.collectLatest { uid ->
                if (uid == null) return@collectLatest
                syncAccount(UserPaths(FirebaseFirestore.getInstance(), uid).userDoc)
            }
        }
    }

    private suspend fun syncAccount(userDoc: DocumentReference) {
        // First time this account is used: upload this phone's settings.
        try {
            val snapshot = userDoc.get(Source.SERVER).await()
            if (snapshot.get(FIELD) == null) {
                val local = settingsRepository.settings.first()
                userDoc.set(mapOf(FIELD to local.toMap()), SetOptions.merge())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't check cloud settings (probably offline)", e)
        }

        coroutineScope {
            // Cloud -> phone: whenever the account's settings change (here or on another phone).
            launch {
                userDoc.documentFlow().collect { snapshot ->
                    snapshot.toAppSettings()?.let { settingsRepository.setAll(it) }
                }
            }

            // Phone -> cloud: whenever a setting is changed on this phone.
            // drop(1) skips the value that's already there when syncing starts,
            // so signing in never overwrites the account's settings with this phone's.
            launch {
                settingsRepository.settings
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { settings ->
                        userDoc.set(mapOf(FIELD to settings.toMap()), SetOptions.merge())
                            .addOnFailureListener { Log.w(TAG, "Upload failed", it) }
                    }
            }
        }
    }

    private companion object {
        const val FIELD = "settings"
    }
}

// ---------------- Converting ----------------

private fun AppSettings.toMap(): Map<String, Any> = mapOf(
    "currencyCode" to currencyCode,
    "themeMode" to themeMode.name,
    "periodMode" to periodMode.name,
    "paydayDay" to paydayDay,
    "mascot" to mascot.name,
    "roastMode" to roastMode,
    "soundEffects" to soundEffects
)

/** Reads users/{uid}.settings. Anything missing or unknown falls back to the default. */
private fun DocumentSnapshot.toAppSettings(): AppSettings? {
    @Suppress("UNCHECKED_CAST")
    val map = get("settings") as? Map<String, Any?> ?: return null
    val defaults = AppSettings()
    return AppSettings(
        currencyCode = map["currencyCode"] as? String ?: defaults.currencyCode,
        themeMode = enumOr(map["themeMode"], defaults.themeMode),
        periodMode = enumOr(map["periodMode"], defaults.periodMode),
        paydayDay = (map["paydayDay"] as? Number)?.toInt()?.coerceIn(1, 31) ?: defaults.paydayDay,
        mascot = enumOr(map["mascot"], defaults.mascot),
        roastMode = map["roastMode"] as? Boolean ?: defaults.roastMode,
        soundEffects = map["soundEffects"] as? Boolean ?: defaults.soundEffects
    )
}

private inline fun <reified T : Enum<T>> enumOr(value: Any?, default: T): T =
    (value as? String)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

/** Watches one document for changes. */
private fun DocumentReference.documentFlow(): Flow<DocumentSnapshot> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) {
            Log.w(TAG, "Listener stopped", error)
            close()
            return@addSnapshotListener
        }
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { registration.remove() }
}
