package com.sosina.terefe.budgetingapp.di

import android.content.Context
import androidx.room.Room
import com.sosina.terefe.budgetingapp.data.local.AppDatabase
import com.sosina.terefe.budgetingapp.data.local.dao.BudgetPeriodDao
import com.sosina.terefe.budgetingapp.data.local.dao.IncomeDao
import com.sosina.terefe.budgetingapp.data.local.dao.LabelDao
import com.sosina.terefe.budgetingapp.data.local.dao.TransactionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Tells Hilt how to create the database and its DAOs.
 * SingletonComponent means these live as long as the app does.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton // only one database connection for the whole app
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "budget.db")
            // DEVELOPMENT ONLY: if the tables change, wipe the database
            // instead of crashing. Must be replaced with proper migrations
            // before the app is released, or users would lose their data.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideLabelDao(db: AppDatabase): LabelDao = db.labelDao()

    @Provides
    fun provideBudgetPeriodDao(db: AppDatabase): BudgetPeriodDao = db.budgetPeriodDao()

    @Provides
    fun provideIncomeDao(db: AppDatabase): IncomeDao = db.incomeDao()

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()
}
