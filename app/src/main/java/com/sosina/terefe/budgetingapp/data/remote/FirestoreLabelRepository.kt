package com.sosina.terefe.budgetingapp.data.remote

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.sosina.terefe.budgetingapp.data.local.DefaultLabels
import com.sosina.terefe.budgetingapp.data.repository.LabelRepository
import com.sosina.terefe.budgetingapp.domain.model.Label
import com.sosina.terefe.budgetingapp.domain.model.LabelType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Labels for a signed-in user, stored in Firestore.
 * Created for one specific user (paths.uid); a new one is made after switching accounts.
 */
class FirestoreLabelRepository(
    private val paths: UserPaths
) : LabelRepository {

    private val seedLock = Mutex()

    override fun observeLabels(type: LabelType): Flow<List<Label>> =
        paths.labels
            .whereEqualTo("type", type.name)
            .snapshotFlow()
            .map { snapshot ->
                snapshot.documents
                    .mapNotNull { it.toLabel() }
                    .sortedWith(compareBy({ it.sortOrder }, { it.name }))
            }

    override suspend fun getLabel(id: String): Label? =
        paths.labels.document(id).get().await().toLabel()

    override suspend fun getOtherExpenseLabel(): Label {
        findBySystemKey(Label.SYSTEM_KEY_EXPENSE_OTHER)?.let { return it }
        ensureDefaultLabels()
        return findBySystemKey(Label.SYSTEM_KEY_EXPENSE_OTHER)
            ?: error("The \"Other\" label is missing")
    }

    /**
     * Adds the default labels the first time this account is used,
     * and makes sure "Other" always exists.
     *
     * Built-in labels use their systemKey as their ID (like "expense_food"),
     * so if two phones do this at the same moment, they write the same labels
     * instead of creating duplicates.
     */
    override suspend fun ensureDefaultLabels() = seedLock.withLock {
        runCatching {
            val alreadySeeded = paths.userDoc.get().await().getBoolean("labelsSeeded") == true
            val hasAnyLabel = !paths.labels.limit(1).get().await().isEmpty

            if (!alreadySeeded && !hasAnyLabel) {
                writeDefaultLabels()
            } else if (findBySystemKey(Label.SYSTEM_KEY_EXPENSE_OTHER) == null) {
                val other = DefaultLabels.createOtherExpense(sortOrder = 999)
                paths.labels.document(Label.SYSTEM_KEY_EXPENSE_OTHER)
                    .set(other.toLabel(id = Label.SYSTEM_KEY_EXPENSE_OTHER).toMap())
                    .logFailures("Restore Other label")
            }

            paths.userDoc.set(mapOf("labelsSeeded" to true), SetOptions.merge())
                .logFailures("Mark labels seeded")
        }
        // If offline on a brand-new account, this quietly tries again next time.
        Unit
    }

    override suspend fun addLabel(
        name: String,
        emoji: String,
        color: Long,
        type: LabelType,
        parentId: String?
    ): Label {
        val nextSortOrder = paths.labels.whereEqualTo("type", type.name).get().await()
            .documents.mapNotNull { it.getLong("sortOrder") }
            .maxOrNull()?.plus(1)?.toInt() ?: 0

        val label = Label(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            emoji = emoji,
            color = color,
            type = type,
            parentId = parentId,
            sortOrder = nextSortOrder
        )
        paths.labels.document(label.id).set(label.toMap()).logFailures("Add label")
        return label
    }

    override suspend fun updateLabel(label: Label) {
        paths.labels.document(label.id)
            .set(label.copy(name = label.name.trim()).toMap())
            .logFailures("Update label")
    }

    /**
     * Firestore has no automatic "cascade" like Room, so the clean-up
     * Room did by itself is done by hand here:
     * - top-level label: move its expenses and income, delete its sub-labels
     * - sub-label: its expenses stay in the parent label
     */
    override suspend fun deleteLabel(label: Label, moveToLabelId: String?) {
        require(label.canDelete) { "The \"Other\" label can't be deleted." }
        val writes = mutableListOf<(com.google.firebase.firestore.WriteBatch) -> Unit>()

        if (!label.isSubLabel) {
            paths.transactions.whereEqualTo("labelId", label.id).get().await().documents.forEach { doc ->
                writes += { b -> b.update(doc.reference, mapOf("labelId" to moveToLabelId, "subLabelId" to null)) }
            }
            paths.incomes.whereEqualTo("labelId", label.id).get().await().documents.forEach { doc ->
                writes += { b -> b.update(doc.reference, "labelId", moveToLabelId) }
            }
            paths.labels.whereEqualTo("parentId", label.id).get().await().documents.forEach { doc ->
                writes += { b -> b.delete(doc.reference) }
            }
        } else {
            paths.transactions.whereEqualTo("subLabelId", label.id).get().await().documents.forEach { doc ->
                writes += { b -> b.update(doc.reference, "subLabelId", FieldValue.delete()) }
            }
        }

        writes += { b -> b.delete(paths.labels.document(label.id)) }
        paths.db.writeInBatches(writes)
    }

    // ---------------- Helpers ----------------

    private suspend fun findBySystemKey(key: String): Label? =
        paths.labels.whereEqualTo("systemKey", key).limit(1).get().await()
            .documents.firstOrNull()?.toLabel()

    private fun writeDefaultLabels() {
        val defaults = DefaultLabels.createAll()
        // Swap the random IDs for the stable systemKey IDs, keeping parent links intact.
        val newIds = defaults.associate { it.id to (it.systemKey ?: it.id) }

        val writes = defaults.map { entity ->
            val label = entity.toLabel(id = newIds.getValue(entity.id))
                .copy(parentId = entity.parentId?.let { newIds[it] })
            val write: (com.google.firebase.firestore.WriteBatch) -> Unit =
                { b -> b.set(paths.labels.document(label.id), label.toMap()) }
            write
        }
        paths.db.writeInBatches(writes)
    }
}

// ---------------- Converting between Firestore documents and Labels ----------------

internal fun Label.toMap(): Map<String, Any?> = mapOf(
    "name" to name,
    "emoji" to emoji,
    "color" to color,
    "type" to type.name,
    "parentId" to parentId,
    "systemKey" to systemKey,
    "isHidden" to isHidden,
    "sortOrder" to sortOrder
)

internal fun DocumentSnapshot.toLabel(): Label? {
    if (!exists()) return null
    val name = getString("name") ?: return null
    val type = getString("type")?.let { t -> LabelType.entries.firstOrNull { it.name == t } } ?: return null
    return Label(
        id = id,
        name = name,
        emoji = getString("emoji") ?: "🏷️",
        color = getLong("color") ?: 0xFF90A4AE,
        type = type,
        parentId = getString("parentId"),
        systemKey = getString("systemKey"),
        isHidden = getBoolean("isHidden") ?: false,
        sortOrder = (getLong("sortOrder") ?: 0L).toInt()
    )
}

/** Turns a Room default-label row into a Label with a chosen ID. */
private fun com.sosina.terefe.budgetingapp.data.local.LabelEntity.toLabel(id: String) = Label(
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
