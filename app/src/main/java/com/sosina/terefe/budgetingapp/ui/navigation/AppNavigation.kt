package com.sosina.terefe.budgetingapp.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.sosina.terefe.budgetingapp.ui.achievements.AchievementsScreen
import com.sosina.terefe.budgetingapp.ui.dashboard.DashboardScreen
import com.sosina.terefe.budgetingapp.ui.dashboard.DeletedItem
import com.sosina.terefe.budgetingapp.ui.expense.ExpenseEditScreen
import com.sosina.terefe.budgetingapp.ui.income.IncomeEditScreen
import com.sosina.terefe.budgetingapp.ui.labels.LabelsScreen
import com.sosina.terefe.budgetingapp.ui.quickentry.QuickEntryScreen
import com.sosina.terefe.budgetingapp.ui.settings.SettingsScreen
import com.sosina.terefe.budgetingapp.ui.split.SplitBillScreen
import com.sosina.terefe.budgetingapp.ui.stats.StatsScreen
import com.sosina.terefe.budgetingapp.ui.transactions.TransactionsScreen
import kotlinx.serialization.Serializable

// ---------------- Routes (the "address" of each screen) ----------------

@Serializable
object DashboardRoute

@Serializable
object SettingsRoute

@Serializable
object LabelsRoute

@Serializable
object TransactionsRoute

@Serializable
object StatsRoute

@Serializable
object QuickEntryRoute

@Serializable
object SplitBillRoute

@Serializable
object AchievementsRoute

/** incomeId = null means "add new income". Must match the ViewModel's key name. */
@Serializable
data class IncomeEditRoute(val incomeId: String? = null)

/** transactionId = null means "add new expense". startWithCamera opens the camera right away. */
@Serializable
data class ExpenseEditRoute(
    val transactionId: String? = null,
    val startWithCamera: Boolean = false,
    val sharedImageUri: String? = null   // an image shared from another app, to scan right away
)

// ---------------- The navigation map ----------------

@Composable
fun AppNavigation(
    sharedImageUri: Uri? = null,
    onSharedImageHandled: () -> Unit = {}
) {
    val navController = rememberNavController()

    // Holds something just deleted, so the dashboard can offer "Undo".
    var deletedItem by remember { mutableStateOf<DeletedItem?>(null) }

    NavHost(navController = navController, startDestination = DashboardRoute) {

        composable<DashboardRoute> {
            DashboardScreen(
                onAddExpense = { navController.navigate(ExpenseEditRoute()) },
                onEditExpense = { id -> navController.navigate(ExpenseEditRoute(transactionId = id)) },
                onScanReceipt = { navController.navigate(ExpenseEditRoute(startWithCamera = true)) },
                onQuickAdd = { navController.navigate(QuickEntryRoute) },
                onAddIncome = { navController.navigate(IncomeEditRoute()) },
                onEditIncome = { id -> navController.navigate(IncomeEditRoute(incomeId = id)) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenHistory = { navController.navigate(TransactionsRoute) },
                onOpenSplit = { navController.navigate(SplitBillRoute) },
                onOpenStats = { navController.navigate(StatsRoute) },
                deletedItem = deletedItem,
                onDeletedItemHandled = { deletedItem = null }
            )
        }

        composable<StatsRoute> {
            StatsScreen(onBack = { navController.goBack() })
        }

        composable<QuickEntryRoute> {
            QuickEntryScreen(onFinished = { navController.goBack() })
        }

        composable<SplitBillRoute> {
            SplitBillScreen(onBack = { navController.goBack() })
        }

        composable<TransactionsRoute> {
            TransactionsScreen(
                onBack = { navController.goBack() },
                onEditExpense = { id -> navController.navigate(ExpenseEditRoute(transactionId = id)) },
                onEditIncome = { id -> navController.navigate(IncomeEditRoute(incomeId = id)) },
                deletedItem = deletedItem,
                onDeletedItemHandled = { deletedItem = null }
            )
        }

        composable<ExpenseEditRoute> { entry ->
            val route = entry.toRoute<ExpenseEditRoute>()
            ExpenseEditScreen(
                startWithCamera = route.startWithCamera,
                sharedImageUri = route.sharedImageUri,
                onFinished = { navController.goBack() },
                onDeleted = { transaction ->
                    deletedItem = DeletedItem.ExpenseItem(transaction)
                    navController.goBack()
                }
            )
        }

        composable<IncomeEditRoute> {
            IncomeEditScreen(
                onFinished = { navController.goBack() },
                onDeleted = { income ->
                    deletedItem = DeletedItem.IncomeItem(income)
                    navController.goBack()
                }
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.goBack() },
                onOpenLabels = { navController.navigate(LabelsRoute) },
                onOpenAchievements = { navController.navigate(AchievementsRoute) }
            )
        }

        composable<LabelsRoute> {
            LabelsScreen(onBack = { navController.goBack() })
        }

        composable<AchievementsRoute> {
            AchievementsScreen(onBack = { navController.goBack() })
        }
    }

    // A shared image arrived: open the Add Expense form to scan it.
    LaunchedEffect(sharedImageUri) {
        val uri = sharedImageUri ?: return@LaunchedEffect
        navController.navigate(ExpenseEditRoute(sharedImageUri = uri.toString()))
        onSharedImageHandled()
    }
}

/** Goes back one screen, but never past the dashboard. */
private fun NavController.goBack() {
    if (previousBackStackEntry != null) popBackStack()
}
