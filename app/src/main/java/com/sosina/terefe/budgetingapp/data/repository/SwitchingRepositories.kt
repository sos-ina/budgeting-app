@file:OptIn(ExperimentalCoroutinesApi::class)

package com.sosina.terefe.budgetingapp.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.sosina.terefe.budgetingapp.data.auth.AuthRepository
import com.sosina.terefe.budgetingapp.data.remote.FirestoreBudgetPeriodRepository
import com.sosina.terefe.budgetingapp.data.remote.FirestoreIncomeRepository
import com.sosina.terefe.budgetingapp.data.remote.FirestoreLabelRepository
import com.sosina.terefe.budgetingapp.data.remote.FirestoreTransactionRepository
import com.sosina.terefe.budgetingapp.data.remote.UserPaths
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.domain.model.BudgetPeriod
import com.sosina.terefe.budgetingapp.domain.model.DaySpending
import com.sosina.terefe.budgetingapp.domain.model.Income
import com.sosina.terefe.budgetingapp.domain.model.Label
import com.sosina.terefe.budgetingapp.domain.model.LabelSpending
import com.sosina.terefe.budgetingapp.domain.model.LabelType
import com.sosina.terefe.budgetingapp.domain.model.Transaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** One complete set of repositories: either all Room, or all Firestore for one user. */
class RepositorySet(
    val labels: LabelRepository,
    val periods: BudgetPeriodRepository,
    val incomes: IncomeRepository,
    val transactions: TransactionRepository
)

/**
 * Decides which set of repositories to use:
 * - nobody signed in (guest) -> Room, on this phone
 * - signed in                -> Firestore, for that account
 */
@Singleton
class DataSourceProvider @Inject constructor(
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    roomLabels: RoomLabelRepository,
    roomPeriods: RoomBudgetPeriodRepository,
    roomIncomes: RoomIncomeRepository,
    roomTransactions: RoomTransactionRepository
) {
    private val roomSet = RepositorySet(roomLabels, roomPeriods, roomIncomes, roomTransactions)

    // The Firestore set is built once per account and reused.
    private var cachedUid: String? = null
    private var cachedSet: RepositorySet? = null

    /** The right set for the current moment. */
    fun now(): RepositorySet = setFor(authRepository.currentUid())

    /** The right set, updating whenever someone signs in or out. */
    val current: Flow<RepositorySet> = authRepository.uid.map { setFor(it) }

    @Synchronized
    private fun setFor(uid: String?): RepositorySet {
        if (uid == null) {
            // Signed out: forget the old account's repositories completely.
            cachedUid = null
            cachedSet = null
            return roomSet
        }
        cachedSet?.takeIf { cachedUid == uid }?.let { return it }

        // Always ask for the current Firestore instance (a new one after sign-out).
        val paths = UserPaths(FirebaseFirestore.getInstance(), uid)
        val periods = FirestoreBudgetPeriodRepository(paths, settingsRepository)
        return RepositorySet(
            labels = FirestoreLabelRepository(paths),
            periods = periods,
            incomes = FirestoreIncomeRepository(paths, periods),
            transactions = FirestoreTransactionRepository(paths, periods)
        ).also {
            cachedUid = uid
            cachedSet = it
        }
    }
}

// ============================ Labels ============================

@Singleton
class SwitchingLabelRepository @Inject constructor(
    private val sources: DataSourceProvider
) : LabelRepository {

    override fun observeLabels(type: LabelType): Flow<List<Label>> =
        sources.current.flatMapLatest { it.labels.observeLabels(type) }

    override suspend fun getLabel(id: String) = sources.now().labels.getLabel(id)

    override suspend fun getOtherExpenseLabel() = sources.now().labels.getOtherExpenseLabel()

    override suspend fun ensureDefaultLabels() = sources.now().labels.ensureDefaultLabels()

    override suspend fun addLabel(name: String, emoji: String, color: Long, type: LabelType, parentId: String?) =
        sources.now().labels.addLabel(name, emoji, color, type, parentId)

    override suspend fun updateLabel(label: Label) = sources.now().labels.updateLabel(label)

    override suspend fun deleteLabel(label: Label, moveToLabelId: String?) =
        sources.now().labels.deleteLabel(label, moveToLabelId)
}

// ============================ Budget months ============================

@Singleton
class SwitchingBudgetPeriodRepository @Inject constructor(
    private val sources: DataSourceProvider
) : BudgetPeriodRepository {

    override suspend fun getPeriodFor(date: LocalDate) = sources.now().periods.getPeriodFor(date)

    override suspend fun getPreviousPeriod(period: BudgetPeriod) = sources.now().periods.getPreviousPeriod(period)

    override suspend fun getEarliestPeriod() = sources.now().periods.getEarliestPeriod()

    override suspend fun getFinishedPeriods(today: LocalDate) = sources.now().periods.getFinishedPeriods(today)

    override fun observeAllPeriods(): Flow<List<BudgetPeriod>> =
        sources.current.flatMapLatest { it.periods.observeAllPeriods() }
}

// ============================ Income ============================

@Singleton
class SwitchingIncomeRepository @Inject constructor(
    private val sources: DataSourceProvider
) : IncomeRepository {

    override fun observeIncomes(start: LocalDate, end: LocalDate): Flow<List<Income>> =
        sources.current.flatMapLatest { it.incomes.observeIncomes(start, end) }

    override fun observeTotal(start: LocalDate, end: LocalDate): Flow<Long> =
        sources.current.flatMapLatest { it.incomes.observeTotal(start, end) }

    override suspend fun getTotal(start: LocalDate, end: LocalDate) = sources.now().incomes.getTotal(start, end)

    override suspend fun getIncome(id: String) = sources.now().incomes.getIncome(id)

    override suspend fun saveIncome(income: Income) = sources.now().incomes.saveIncome(income)

    override suspend fun deleteIncome(income: Income) = sources.now().incomes.deleteIncome(income)

    override suspend fun getLatestSalaryAmount() = sources.now().incomes.getLatestSalaryAmount()
}

// ============================ Expenses ============================

@Singleton
class SwitchingTransactionRepository @Inject constructor(
    private val sources: DataSourceProvider
) : TransactionRepository {

    override fun observeTransactions(start: LocalDate, end: LocalDate): Flow<List<Transaction>> =
        sources.current.flatMapLatest { it.transactions.observeTransactions(start, end) }

    override fun observeRecent(limit: Int): Flow<List<Transaction>> =
        sources.current.flatMapLatest { it.transactions.observeRecent(limit) }

    override fun observeTransaction(id: String): Flow<Transaction?> =
        sources.current.flatMapLatest { it.transactions.observeTransaction(id) }

    override suspend fun getTransaction(id: String) = sources.now().transactions.getTransaction(id)

    override fun observeTotalSpent(start: LocalDate, end: LocalDate): Flow<Long> =
        sources.current.flatMapLatest { it.transactions.observeTotalSpent(start, end) }

    override suspend fun getTotalSpent(start: LocalDate, end: LocalDate) =
        sources.now().transactions.getTotalSpent(start, end)

    override fun observeSpendingByLabel(start: LocalDate, end: LocalDate): Flow<List<LabelSpending>> =
        sources.current.flatMapLatest { it.transactions.observeSpendingByLabel(start, end) }

    override fun observeSpendingByDay(start: LocalDate, end: LocalDate): Flow<List<DaySpending>> =
        sources.current.flatMapLatest { it.transactions.observeSpendingByDay(start, end) }

    override suspend fun saveTransaction(transaction: Transaction) =
        sources.now().transactions.saveTransaction(transaction)

    override suspend fun deleteTransaction(transaction: Transaction) =
        sources.now().transactions.deleteTransaction(transaction)
}
