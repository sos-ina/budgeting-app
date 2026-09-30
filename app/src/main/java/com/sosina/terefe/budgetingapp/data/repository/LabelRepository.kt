package com.sosina.terefe.budgetingapp.data.repository

import androidx.room.withTransaction
import com.sosina.terefe.budgetingapp.data.local.AppDatabase
import com.sosina.terefe.budgetingapp.data.local.DefaultLabels
import com.sosina.terefe.budgetingapp.data.local.LabelEntity
import com.sosina.terefe.budgetingapp.data.local.dao.IncomeDao
import com.sosina.terefe.budgetingapp.data.local.dao.LabelDao
import com.sosina.terefe.budgetingapp.data.local.dao.TransactionDao
import com.sosina.terefe.budgetingapp.domain.model.Label
import com.sosina.terefe.budgetingapp.domain.model.LabelType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the app can do with labels.
 * Screens only know about this interface, not about Room or Firestore.
 */
interface LabelRepository {

    /** All labels and sub-labels of one type, live-updating. */
    fun observeLabels(type: LabelType): Flow<List<Label>>

    suspend fun getLabel(id: String): Label?

    /** The built-in "Other" label, the default for new expenses. */
    suspend fun getOtherExpenseLabel(): Label

    /** Adds the default labels on first launch, and makes sure "Other" always exists. */
    suspend fun ensureDefaultLabels()

    suspend fun addLabel(
        name: String,
        emoji: String,
        color: Long,
        type: LabelType,
        parentId: String? = null
    ): Label

    suspend fun updateLabel(label: Label)

    /**
     * Deletes a label. Its transactions and income are moved to
     * [moveToLabelId], or become Uncategorized if that's null.
     */
    suspend fun deleteLabel(label: Label, moveToLabelId: String?)
}

/** The guest-mode version, storing labels in the local Room database. */
@Singleton
class RoomLabelRepository @Inject constructor(
    private val database: AppDatabase,
    private val labelDao: LabelDao,
    private val transactionDao: TransactionDao,
    private val incomeDao: IncomeDao
) : LabelRepository {

    // Prevents the defaults from being added twice if called at the same moment.
    private val seedLock = Mutex()

    override fun observeLabels(type: LabelType): Flow<List<Label>> =
        labelDao.observeByType(type).map { list -> list.map { it.toDomain() } }

    override suspend fun getLabel(id: String): Label? =
        labelDao.getById(id)?.toDomain()

    override suspend fun getOtherExpenseLabel(): Label {
        ensureDefaultLabels()
        return labelDao.getBySystemKey(Label.SYSTEM_KEY_EXPENSE_OTHER)!!.toDomain()
    }

    override suspend fun ensureDefaultLabels() = seedLock.withLock {
        if (labelDao.count() == 0) {
            // First launch: add everything.
            labelDao.insertAll(DefaultLabels.createAll())
        } else if (labelDao.getBySystemKey(Label.SYSTEM_KEY_EXPENSE_OTHER) == null) {
            // "Other" must always exist as the fallback.
            labelDao.insert(DefaultLabels.createOtherExpense(labelDao.nextTopLevelSortOrder(LabelType.EXPENSE)))
        }
    }

    override suspend fun addLabel(
        name: String,
        emoji: String,
        color: Long,
        type: LabelType,
        parentId: String?
    ): Label {
        val sortOrder = if (parentId == null) {
            labelDao.nextTopLevelSortOrder(type)
        } else {
            labelDao.nextSubLabelSortOrder(type, parentId)
        }
        val entity = LabelEntity(
            name = name.trim(),
            emoji = emoji,
            color = color,
            type = type,
            parentId = parentId,
            sortOrder = sortOrder
        )
        labelDao.insert(entity)
        return entity.toDomain()
    }

    override suspend fun updateLabel(label: Label) {
        labelDao.update(label.copy(name = label.name.trim()).toEntity())
    }

    override suspend fun deleteLabel(label: Label, moveToLabelId: String?) {
        require(label.canDelete) { "The \"Other\" label can't be deleted." }

        // All steps happen together: either everything succeeds or nothing changes.
        database.withTransaction {
            if (!label.isSubLabel) {
                // Move money to the chosen label first, so nothing becomes orphaned.
                transactionDao.moveToLabel(label.id, moveToLabelId)
                incomeDao.moveToLabel(label.id, moveToLabelId)
            }
            // For a sub-label, transactions simply stay in the parent label
            // (their subLabelId is cleared automatically by the database).
            labelDao.delete(label.toEntity())
        }
    }
}

// ---------------- Converting between database rows and screen models ----------------

private fun LabelEntity.toDomain() = Label(
    id = id,
    name = name,
    emoji = emoji,
    color = color,
    type = type,
    parentId = parentId,
    systemKey = systemKey,
    isHidden = isHidden,
    sortOrder = sortOrder
)

private fun Label.toEntity() = LabelEntity(
    id = id,
    name = name,
    emoji = emoji,
    color = color,
    type = type,
    parentId = parentId,
    systemKey = systemKey,
    isHidden = isHidden,
    sortOrder = sortOrder
)
