package com.sosina.terefe.budgetingapp.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sosina.terefe.budgetingapp.data.auth.AuthRepository
import com.sosina.terefe.budgetingapp.data.auth.SessionState
import com.sosina.terefe.budgetingapp.data.auth.SignInResult
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.domain.model.AppSettings
import com.sosina.terefe.budgetingapp.domain.model.MascotType
import com.sosina.terefe.budgetingapp.domain.model.PeriodMode
import com.sosina.terefe.budgetingapp.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The "brain" of the Settings screen.
 * The screen only displays things and reports clicks;
 * the ViewModel talks to the repository.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    /** Current settings, or null while they're still loading. */
    val settings: StateFlow<AppSettings?> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    /** Guest, signed in, etc. */
    val session: StateFlow<SessionState> = authRepository.session.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SessionState.Loading
    )

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    /** An error from signing in, shown under the account section. */
    private val _accountMessage = MutableStateFlow<String?>(null)
    val accountMessage: StateFlow<String?> = _accountMessage.asStateFlow()

    /** For guests: sign up from Settings without losing their place. */
    fun signInWithGoogle(activityContext: Context) {
        if (_isSigningIn.value) return
        _isSigningIn.value = true
        _accountMessage.value = null
        viewModelScope.launch {
            val result = authRepository.signInWithGoogle(activityContext)
            if (result is SignInResult.Error) _accountMessage.value = result.message
            _isSigningIn.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    fun setCurrency(code: String) {
        viewModelScope.launch { repository.setCurrency(code) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setPeriodMode(mode: PeriodMode) {
        viewModelScope.launch { repository.setPeriodMode(mode) }
    }

    fun setPaydayDay(day: Int) {
        viewModelScope.launch { repository.setPaydayDay(day) }
    }

    fun setMascot(mascot: MascotType) {
        viewModelScope.launch { repository.setMascot(mascot) }
    }

    fun setRoastMode(enabled: Boolean) {
        viewModelScope.launch { repository.setRoastMode(enabled) }
    }

    fun setSoundEffects(enabled: Boolean) {
        viewModelScope.launch { repository.setSoundEffects(enabled) }
    }
}
