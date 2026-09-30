package com.sosina.terefe.budgetingapp.di

import com.sosina.terefe.budgetingapp.data.repository.BudgetPeriodRepository
import com.sosina.terefe.budgetingapp.data.repository.IncomeRepository
import com.sosina.terefe.budgetingapp.data.repository.LabelRepository
import com.sosina.terefe.budgetingapp.data.repository.SwitchingBudgetPeriodRepository
import com.sosina.terefe.budgetingapp.data.repository.SwitchingIncomeRepository
import com.sosina.terefe.budgetingapp.data.repository.SwitchingLabelRepository
import com.sosina.terefe.budgetingapp.data.repository.SwitchingTransactionRepository
import com.sosina.terefe.budgetingapp.data.repository.TransactionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Screens always get the "switching" repositories, which use
 * Room for guests and Firestore for signed-in users.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindLabelRepository(impl: SwitchingLabelRepository): LabelRepository

    @Binds
    @Singleton
    abstract fun bindBudgetPeriodRepository(impl: SwitchingBudgetPeriodRepository): BudgetPeriodRepository

    @Binds
    @Singleton
    abstract fun bindIncomeRepository(impl: SwitchingIncomeRepository): IncomeRepository

    @Binds
    @Singleton
    abstract fun bindTransactionRepository(impl: SwitchingTransactionRepository): TransactionRepository
}
