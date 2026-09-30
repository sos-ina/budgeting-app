package com.sosina.terefe.budgetingapp.ui.welcome

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sosina.terefe.budgetingapp.data.auth.AuthRepository
import com.sosina.terefe.budgetingapp.data.auth.SignInResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WelcomeUiState(
    val isWorking: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WelcomeUiState())
    val uiState: StateFlow<WelcomeUiState> = _uiState.asStateFlow()

    fun continueAsGuest() {
        viewModelScope.launch { authRepository.continueAsGuest() }
    }

    /**
     * Sign in and sign up both open Google's account picker.
     * The difference (new vs existing account data) is handled
     * once cloud sync is added.
     *
     * The screen's context is only used during this call, never stored.
     */
    fun signInWithGoogle(activityContext: Context) {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(isWorking = true, error = null) }

        viewModelScope.launch {
            when (val result = authRepository.signInWithGoogle(activityContext)) {
                // On success the session changes and the app moves on by itself.
                is SignInResult.Success -> _uiState.update { it.copy(isWorking = false) }
                SignInResult.Cancelled -> _uiState.update { it.copy(isWorking = false) }
                is SignInResult.Error -> _uiState.update {
                    it.copy(isWorking = false, error = result.message)
                }
            }
        }
    }
}
