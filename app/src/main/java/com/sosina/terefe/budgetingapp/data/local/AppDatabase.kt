package com.sosina.terefe.budgetingapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.sosina.terefe.budgetingapp.data.local.dao.BudgetPeriodDao
import com.sosina.terefe.budgetingapp.data.local.dao.IncomeDao
import com.sosina.terefe.budgetingapp.data.local.dao.LabelDao
import com.sosina.terefe.budgetingapp.data.local.dao.TransactionDao

/**
 * The database itself. It lists every table and gives access to each DAO.
 *
 * "version" must go up by 1 every time a table changes (a column added,
 * renamed, etc.), so Room knows the stored data needs updating.
 */
@Database(
    entities = [
        LabelEntity::class,
        BudgetPeriodEntity::class,
        IncomeEntity::class,
        TransactionEntity::class,
        TransactionItemEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun labelDao(): LabelDao
    abstract fun budgetPeriodDao(): BudgetPeriodDao
    abstract fun incomeDao(): IncomeDao
    abstract fun transactionDao(): TransactionDao
}
