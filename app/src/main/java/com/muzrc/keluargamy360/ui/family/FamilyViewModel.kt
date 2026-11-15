package com.muzrc.keluargamy360.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muzrc.keluargamy360.data.model.FamilyGroup
import com.muzrc.keluargamy360.data.repository.FamilyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FamilyUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val joinedFamily: FamilyGroup? = null
)

class FamilyViewModel(
    private val repository: FamilyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FamilyUiState())
    val uiState: StateFlow<FamilyUiState> = _uiState.asStateFlow()

    fun createFamily(familyName: String, pin: String) {
        when {
            familyName.isBlank() -> {
                _uiState.update { it.copy(errorMessage = "Sila masukkan nama keluarga (cth: Keluarga Hj. Ahmad).") }
                return
            }
            pin.length != 6 || !pin.all { it.isDigit() } -> {
                _uiState.update { it.copy(errorMessage = "PIN Keselamatan mesti tepat 6 digit nombor.") }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.createFamily(familyName, pin).fold(
                onSuccess = { group ->
                    _uiState.update { it.copy(isLoading = false, joinedFamily = group) }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                }
            )
        }
    }

    fun joinFamily(inviteCode: String, pin: String) {
        when {
            inviteCode.trim().length != 6 -> {
                _uiState.update { it.copy(errorMessage = "Kod jemputan mesti 6 aksara.") }
                return
            }
            pin.length != 6 || !pin.all { it.isDigit() } -> {
                _uiState.update { it.copy(errorMessage = "PIN Keselamatan mesti 6 digit nombor.") }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.joinFamily(inviteCode, pin).fold(
                onSuccess = { group ->
                    _uiState.update { it.copy(isLoading = false, joinedFamily = group) }
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
