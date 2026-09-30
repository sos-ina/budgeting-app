package com.sosina.terefe.budgetingapp.data.remote

import android.util.Log
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "Firestore"

/**
 * Where one user's data lives in Firestore:
 *
 * users/{uid}                  <- the user's own document
 *   labels/{labelId}
 *   periods/{periodId}
 *   incomes/{incomeId}
 *   transactions/{id}          <- items are stored inside, as a list
 *
 * The security rules only let each user touch their own users/{uid}.
 */
class UserPaths(val db: FirebaseFirestore, val uid: String) {
    val userDoc: DocumentReference get() = db.collection("users").document(uid)
    val labels: CollectionReference get() = userDoc.collection("labels")
    val periods: CollectionReference get() = userDoc.collection("periods")
    val incomes: CollectionReference get() = userDoc.collection("incomes")
    val transactions: CollectionReference get() = userDoc.collection("transactions")
}

/**
 * Watches a query and sends new results every time the data changes,
 * including changes made on this phone while offline.
 */
fun Query.snapshotFlow(): Flow<QuerySnapshot> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) {
            // Usually "permission denied" right after signing out. End quietly instead of crashing.
            Log.w(TAG, "Listener stopped", error)
            close()
            return@addSnapshotListener
        }
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { registration.remove() }
}

/**
 * Applies many writes, split into batches (Firestore allows at most 500 per batch).
 *
 * Important: this does NOT wait for the server. Firestore saves the change on the
 * phone instantly and uploads it when online. Waiting for the server would make
 * the app freeze whenever there's no internet.
 */
fun FirebaseFirestore.writeInBatches(writes: List<(WriteBatch) -> Unit>) {
    writes.chunked(450).forEach { chunk ->
        val batch = batch()
        chunk.forEach { write -> write(batch) }
        batch.commit().addOnFailureListener { Log.w(TAG, "Batch write failed", it) }
    }
}

/** Logs failed single writes (they're retried automatically, but it's useful to know). */
fun com.google.android.gms.tasks.Task<Void>.logFailures(what: String) {
    addOnFailureListener { Log.w(TAG, "$what failed", it) }
}
