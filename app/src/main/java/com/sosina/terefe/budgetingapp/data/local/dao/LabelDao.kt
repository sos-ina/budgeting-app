package com.sosina.terefe.budgetingapp.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.sosina.terefe.budgetingapp.data.local.LabelEntity
import com.sosina.terefe.budgetingapp.domain.model.LabelType
import kotlinx.coroutines.flow.Flow

/**
 * Functions returning Flow keep watching the table and send
 * fresh results whenever it changes, so screens update automatically.
 * "suspend" functions run once and return a single result.
 */
@Dao
interface LabelDao {

    /** All labels and sub-labels of one type (expense or income), in display order. */
    @Query("SELECT * FROM labels WHERE type = :type ORDER BY sortOrder, name")
    fun observeByType(type: LabelType): Flow<List<LabelEntity>>

    @Query("SELECT * FROM labels ORDER BY type, sortOrder, name")
    fun observeAll(): Flow<List<LabelEntity>>

    @Query("SELECT * FROM labels WHERE id = :id")
    suspend fun getById(id: String): LabelEntity?

    /** Finds a built-in label, e.g. getBySystemKey("expense_other"). */
    @Query("SELECT * FROM labels WHERE systemKey = :key")
    suspend fun getBySystemKey(key: String): LabelEntity?

    @Query("SELECT COUNT(*) FROM labels")
    suspend fun count(): Int

    /** The sort position for a new top-level label, so it appears at the end. */
    @Query(
        "SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM labels " +
            "WHERE type = :type AND parentId IS NULL"
    )
    suspend fun nextTopLevelSortOrder(type: LabelType): Int

    /** The sort position for a new sub-label, so it appears after its siblings. */
    @Query(
        "SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM labels " +
            "WHERE type = :type AND parentId = :parentId"
    )
    suspend fun nextSubLabelSortOrder(type: LabelType, parentId: String): Int

    @Insert
    suspend fun insert(label: LabelEntity)

    @Insert
    suspend fun insertAll(labels: List<LabelEntity>)

    @Update
    suspend fun update(label: LabelEntity)

    @Delete
    suspend fun delete(label: LabelEntity)
}
