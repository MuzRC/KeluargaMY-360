package com.muzrc.keluargamy360.ui.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muzrc.keluargamy360.data.model.User
import com.muzrc.keluargamy360.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val authenticatedUser: User? = null
)

class AuthViewModel(
    private val repository: AuthRepository = AuthRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun loginWithEmail(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Sila isi emel dan kata laluan anda.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.signInWithEmail(email, password)
            result.fold(
                onSuccess = { user ->
                    _uiState.update { it.copy(isLoading = false, authenticatedUser = user) }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun registerWithEmail(name: String, email: String, password: String, confirmPassword: String) {
        when {
            name.isBlank() -> {
                _uiState.update { it.copy(errorMessage = "Sila masukkan nama paparan anda.") }
                return
            }
            email.isBlank() -> {
                _uiState.update { it.copy(errorMessage = "Sila masukkan alamat emel.") }
                return
            }
            password.length < 6 -> {
                _uiState.update { it.copy(errorMessage = "Kata laluan mesti sekurang-kurangnya 6 aksara.") }
                return
            }
            password != confirmPassword -> {
                _uiState.update { it.copy(errorMessage = "Pengesahan kata laluan tidak sepadan.") }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.registerWithEmail(name, email, password)
            result.fold(
                onSuccess = { user ->
                    _uiState.update { it.copy(isLoading = false, authenticatedUser = user) }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun loginWithGoogle(activityContext: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.signInWithGoogle(activityContext)
            result.fold(
                onSuccess = { user ->
                    _uiState.update { it.copy(isLoading = false, authenticatedUser = user) }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
