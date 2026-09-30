package com.sosina.terefe.budgetingapp

import android.app.Application
import android.util.Log
import com.sosina.terefe.budgetingapp.data.auth.AuthRepository
import com.sosina.terefe.budgetingapp.data.funstate.AchievementManager
import com.sosina.terefe.budgetingapp.data.repository.BudgetPeriodRepository
import com.sosina.terefe.budgetingapp.data.repository.LabelRepository
import com.sosina.terefe.budgetingapp.data.sync.AccountMigrationManager
import com.sosina.terefe.budgetingapp.data.sync.SettingsSyncManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Application class runs once when the app starts, before any screen.
 * @HiltAndroidApp tells Hilt to set itself up here, so it can provide
 * things like the database to the rest of the app.
 */
@HiltAndroidApp
class BudgetApp : Application() {

    @Inject
    lateinit var labelRepository: LabelRepository

    @Inject
    lateinit var budgetPeriodRepository: BudgetPeriodRepository

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var accountMigrationManager: AccountMigrationManager

    @Inject
    lateinit var settingsSyncManager: SettingsSyncManager

    @Inject
    lateinit var achievementManager: AchievementManager

    /**
     * A background scope that lives as long as the app.
     * SupervisorJob means one failed task doesn't cancel the others.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate() // Hilt fills in labelRepository here

        // Runs at app start, and again every time someone signs in or out.
        appScope.launch {
            authRepository.uid.collect { uid ->
                // 1. Make sure this account (or guest mode) has labels and a current month.
                try {
                    labelRepository.ensureDefaultLabels()
                    budgetPeriodRepository.getCurrentPeriod()
                } catch (e: Exception) {
                    Log.w("BudgetApp", "Setup for this account failed, will retry next launch", e)
                }
                // 2. If someone just signed in, check whether to offer saving guest data.
                try {
                    accountMigrationManager.checkAfterSignIn(uid)
                } catch (e: Exception) {
                    Log.w("BudgetApp", "Guest data check failed, will retry next launch", e)
                }
                // 3. Past months may have earned Survived the Month or Super Saver.
                achievementManager.checkFinishedMonths()
            }
        }

        // Keeps settings in step with the signed-in account.
        settingsSyncManager.start(appScope)
    }
}
