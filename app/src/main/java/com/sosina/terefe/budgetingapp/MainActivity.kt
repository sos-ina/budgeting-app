package com.sosina.terefe.budgetingapp

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosina.terefe.budgetingapp.data.auth.AuthRepository
import com.sosina.terefe.budgetingapp.data.auth.SessionState
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.domain.model.ThemeMode
import com.sosina.terefe.budgetingapp.ui.account.AccountSetupHost
import com.sosina.terefe.budgetingapp.ui.navigation.AppNavigation
import com.sosina.terefe.budgetingapp.ui.theme.BudgetingAppTheme
import com.sosina.terefe.budgetingapp.ui.welcome.WelcomeScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var authRepository: AuthRepository

    /** An image shared from another app, waiting to be scanned. */
    private val sharedImage = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only on a fresh start, so rotating the screen doesn't scan the same image again.
        if (savedInstanceState == null) {
            sharedImage.value = intent.sharedImageUri()
        }

        setContent {
            val settings by settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = null)
            val session by authRepository.session
                .collectAsStateWithLifecycle(initialValue = SessionState.Loading)

            val current = settings
            if (current != null) {
                val darkTheme = when (current.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }

                LaunchedEffect(darkTheme) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(
                            Color.TRANSPARENT, Color.TRANSPARENT
                        ) { darkTheme },
                        navigationBarStyle = SystemBarStyle.auto(
                            Color.TRANSPARENT, Color.TRANSPARENT
                        ) { darkTheme }
                    )
                }

                BudgetingAppTheme(darkTheme = darkTheme) {
                    when (session) {
                        // Still checking: show nothing for a split second.
                        SessionState.Loading -> Unit

                        // First launch or after sign-out.
                        SessionState.NeedsChoice -> WelcomeScreen()

                        // Guest or signed in: the normal app, plus any sign-in questions on top.
                        SessionState.Guest,
                        is SessionState.SignedIn -> {
                            AppNavigation(
                                sharedImageUri = sharedImage.value,
                                onSharedImageHandled = { sharedImage.value = null }
                            )
                            AccountSetupHost()
                        }
                    }
                }
            }
        }
    }
}

/** The image in a "share" from another app, or null if this wasn't an image share. */
private fun Intent.sharedImageUri(): Uri? =
    if (action == Intent.ACTION_SEND && type?.startsWith("image/") == true) {
        IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        null
    }
