package com.sosina.terefe.budgetingapp.data.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.sosina.terefe.budgetingapp.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

// A small separate file on the phone for session info (kept apart from user settings).
private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/** The signed-in person, as the screens see them. */
data class AccountUser(
    val uid: String,
    val name: String?,
    val email: String?,
    val photoUrl: String?
)

/** Where the app is: still loading, waiting for a choice, guest, or signed in. */
sealed interface SessionState {
    data object Loading : SessionState
    data object NeedsChoice : SessionState       // show the Welcome screen
    data object Guest : SessionState
    data class SignedIn(val user: AccountUser) : SessionState
}

/** What happened when the user tried to sign in. */
sealed interface SignInResult {
    /** isNewUser = true the very first time this Google account uses the app. */
    data class Success(val isNewUser: Boolean) : SignInResult
    data object Cancelled : SignInResult
    data class Error(val message: String) : SignInResult
}

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val credentialManager = CredentialManager.create(context)

    private object Keys {
        val GUEST_CHOSEN = booleanPreferencesKey("guest_chosen")
    }

    /** The Firebase user, updating live whenever someone signs in or out. */
    private val firebaseUser: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /** Whether the user tapped "Continue as guest" before. */
    private val guestChosen: Flow<Boolean> = context.sessionDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.GUEST_CHOSEN] ?: false }

    /**
     * The current session. A signed-in account always wins;
     * otherwise it's guest mode if chosen, or the Welcome screen.
     */
    val session: Flow<SessionState> = combine(firebaseUser, guestChosen) { user, guest ->
        when {
            user != null -> SessionState.SignedIn(user.toAccountUser())
            guest -> SessionState.Guest
            else -> SessionState.NeedsChoice
        }
    }

    /** The signed-in user's ID, or null for guests. Only changes on sign-in or sign-out. */
    val uid: Flow<String?> = firebaseUser.map { it?.uid }.distinctUntilChanged()

    /** The signed-in user's ID right now, or null. */
    fun currentUid(): String? = auth.currentUser?.uid

    suspend fun continueAsGuest() {
        context.sessionDataStore.edit { it[Keys.GUEST_CHOSEN] = true }
    }

    /**
     * Shows Google's account picker, then signs into Firebase with the chosen account.
     *
     * @param activityContext must be the screen's context (not the app's),
     *        because the account picker appears on top of that screen.
     */
    suspend fun signInWithGoogle(activityContext: Context): SignInResult {
        // The Web client ID is read from google-services.json automatically.
        val googleOption = GetSignInWithGoogleOption.Builder(
            serverClientId = context.getString(R.string.default_web_client_id)
        ).build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()

        return try {
            val response = credentialManager.getCredential(activityContext, request)
            val credential = response.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                // Google says who the user is; now hand that proof to Firebase.
                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
                val result = auth.signInWithCredential(firebaseCredential).await()
                SignInResult.Success(isNewUser = result.additionalUserInfo?.isNewUser == true)
            } else {
                SignInResult.Error("Unexpected sign-in response. Please try again.")
            }
        } catch (e: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (e: NoCredentialException) {
            SignInResult.Error("No Google account found on this phone. Add one in phone settings first.")
        } catch (e: GetCredentialException) {
            SignInResult.Error(e.message ?: "Google sign-in failed.")
        } catch (e: GoogleIdTokenParsingException) {
            SignInResult.Error("Couldn't read the Google account. Please try again.")
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            SignInResult.Error(e.localizedMessage ?: "Sign-in failed. Check your internet connection.")
        }
    }

    /** Signs out, removes the account's copy from this phone, and returns to Welcome. */
    suspend fun signOut() {
        auth.signOut()
        runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }

        // Delete this account's cached Firestore data from the phone.
        // Firestore must be shut down first; a fresh instance is created on next use.
        runCatching {
            val db = FirebaseFirestore.getInstance()
            db.terminate().await()
            db.clearPersistence().await()
        }

        context.sessionDataStore.edit { it[Keys.GUEST_CHOSEN] = false }
    }
}

private fun FirebaseUser.toAccountUser() = AccountUser(
    uid = uid,
    name = displayName,
    email = email,
    photoUrl = photoUrl?.toString()
)
