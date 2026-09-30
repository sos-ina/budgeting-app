package com.sosina.terefe.budgetingapp.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.WriteBatch
import com.sosina.terefe.budgetingapp.data.local.AppDatabase
import com.sosina.terefe.budgetingapp.data.local.dao.BudgetPeriodDao
import com.sosina.terefe.budgetingapp.data.local.dao.IncomeDao
import com.sosina.terefe.budgetingapp.data.local.dao.LabelDao
import com.sosina.terefe.budgetingapp.data.local.dao.TransactionDao
import com.sosina.terefe.budgetingapp.data.remote.UserPaths
import com.sosina.terefe.budgetingapp.data.remote.toMap
import com.sosina.terefe.budgetingapp.domain.model.BudgetPeriod
import com.sosina.terefe.budgetingapp.domain.model.Income
import com.sosina.terefe.budgetingapp.domain.model.Label
import com.sosina.terefe.budgetingapp.domain.model.Transaction
import com.sosina.terefe.budgetingapp.domain.model.TransactionItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

// Remembers which accounts have already been through the "save guest data?" question.
private val Context.accountSetupDataStore: DataStore<Preferences> by preferencesDataStore(name = "account_setup")

/** What to ask or tell the user right after signing in. */
sealed interface AccountPrompt {
    /** Empty account + guest data on the phone: save it or start fresh? */
    data class SaveGuestData(val uid: String) : AccountPrompt

    /** The account already has data, so guest data is left alone. */
    data object AlreadyHasData : AccountPrompt
}

@Singleton
class AccountMigrationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val labelDao: LabelDao,
    private val periodDao: BudgetPeriodDao,
    private val incomeDao: IncomeDao,
    private val transactionDao: TransactionDao
) {
    private val handledKey = stringSetPreferencesKey("handled_uids")

    private val _prompt = MutableStateFlow<AccountPrompt?>(null)
    val prompt: StateFlow<AccountPrompt?> = _prompt.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Called whenever the signed-in account changes.
     * Decides whether to ask anything, following the rules at the top of this step.
     */
    suspend fun checkAfterSignIn(uid: String?) {
        if (uid == null) {
            _prompt.value = null
            return
        }
        if (isHandled(uid)) return

        val localHasData = incomeDao.count() > 0 || transactionDao.count() > 0
        if (!localHasData) {
            markHandled(uid)
            return
        }

        // Ask the server directly, since the phone has no copy of a new account yet.
        val paths = UserPaths(FirebaseFirestore.getInstance(), uid)
        val accountHasData = try {
            hasAnyDocument(paths.transactions) || hasAnyDocument(paths.incomes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // offline: ask again next time the app starts
        }

        if (accountHasData) {
            markHandled(uid)
            _prompt.value = AccountPrompt.AlreadyHasData
        } else {
            _prompt.value = AccountPrompt.SaveGuestData(uid)
        }
    }

    /** "Start fresh": the account stays empty, guest data stays on the phone. */
    suspend fun startFresh(uid: String) {
        markHandled(uid)
        _prompt.value = null
    }

    /** Closes the "You already have data" message. */
    fun dismissPrompt() {
        _prompt.value = null
    }

    /**
     * "Save my data": uploads everything, then clears the guest copy.
     * Returns true on success. On failure, nothing is deleted and the user can retry.
     */
    suspend fun saveGuestData(uid: String): Boolean {
        _isUploading.value = true
        _error.value = null
        return try {
            upload(UserPaths(FirebaseFirestore.getInstance(), uid))
            withContext(Dispatchers.IO) { database.clearAllTables() }
            markHandled(uid)
            _prompt.value = null
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = "Upload failed. Check your internet connection and try again."
            false
        } finally {
            _isUploading.value = false
        }
    }

    // ---------------- The upload ----------------

    private suspend fun upload(paths: UserPaths) {
        val labels = labelDao.observeAll().first()
        val periods = periodDao.observeAll().first()
        val incomes = incomeDao.getAll()
        val transactions = transactionDao.getAllWithItems()

        // Built-in labels use their systemKey as ID in the cloud (like fresh accounts do),
        // so every reference to them must be translated too.
        val labelIdMap = labels.associate { it.id to (it.systemKey ?: it.id) }
        fun mapLabel(id: String?): String? = id?.let { labelIdMap[it] ?: it }

        val cloudLabels = labels.map { e ->
            Label(
                id = labelIdMap.getValue(e.id),
                name = e.name,
                emoji = e.emoji,
                color = e.color,
                type = e.type,
                parentId = mapLabel(e.parentId),
                systemKey = e.systemKey,
                isHidden = e.isHidden,
                sortOrder = e.sortOrder
            )
        }
        // Period IDs come from the start date, same as the cloud repository.
        val cloudPeriods = periods.map { BudgetPeriod("p_${it.startDate}", it.startDate, it.endDate) }

        val writes = mutableListOf<(WriteBatch) -> Unit>()

        // The new account already got default labels and a current month automatically.
        // Remove any of those that the guest data won't replace.
        val keepLabelIds = cloudLabels.map { it.id }.toSet()
        paths.labels.get(Source.SERVER).await().documents
            .filter { it.id !in keepLabelIds }
            .forEach { doc -> writes += { b -> b.delete(doc.reference) } }

        val keepPeriodIds = cloudPeriods.map { it.id }.toSet()
        paths.periods.get(Source.SERVER).await().documents
            .filter { it.id !in keepPeriodIds }
            .forEach { doc -> writes += { b -> b.delete(doc.reference) } }

        cloudLabels.forEach { label ->
            writes += { b -> b.set(paths.labels.document(label.id), label.toMap()) }
        }
        cloudPeriods.forEach { period ->
            writes += { b -> b.set(paths.periods.document(period.id), period.toMap()) }
        }
        incomes.forEach { e ->
            val income = Income(
                id = e.id,
                amount = e.amount,
                labelId = mapLabel(e.labelId),
                date = e.date,
                note = e.note,
                createdAt = e.createdAt,
                updatedAt = e.updatedAt
            )
            writes += { b -> b.set(paths.incomes.document(income.id), income.toMap()) }
        }
        transactions.forEach { row ->
            val t = row.transaction
            val transaction = Transaction(
                id = t.id,
                amount = t.amount,
                labelId = mapLabel(t.labelId),
                subLabelId = mapLabel(t.subLabelId),
                dateTime = t.dateTime,
                title = t.title,
                note = t.note,
                receiptImageUri = t.receiptImageUri,
                items = row.items.sortedBy { it.position }.map {
                    TransactionItem(id = it.id, name = it.name, price = it.price, quantity = it.quantity)
                },
                createdAt = t.createdAt,
                updatedAt = t.updatedAt
            )
            writes += { b -> b.set(paths.transactions.document(transaction.id), transaction.toMap()) }
        }
        writes += { b -> b.set(paths.userDoc, mapOf("labelsSeeded" to true), SetOptions.merge()) }

        // Unlike normal saves, this DOES wait for the server: the guest copy is only
        // deleted once the upload is confirmed, so nothing can be lost.
        writes.chunked(450).forEach { chunk ->
            val batch = paths.db.batch()
            chunk.forEach { it(batch) }
            batch.commit().await()
        }
    }

    // ---------------- Helpers ----------------

    private suspend fun hasAnyDocument(collection: CollectionReference): Boolean =
        !collection.limit(1).get(Source.SERVER).await().isEmpty

    private suspend fun isHandled(uid: String): Boolean =
        context.accountSetupDataStore.data.map { it[handledKey].orEmpty() }.first().contains(uid)

    private suspend fun markHandled(uid: String) {
        context.accountSetupDataStore.edit { prefs ->
            prefs[handledKey] = prefs[handledKey].orEmpty() + uid
        }
    }
}
