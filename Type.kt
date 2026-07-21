package com.forja.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

data class AuthUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value, error = null) }
    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }

    fun login(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = "Completează emailul și parola.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.login(state.email.trim(), state.password)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                    onSuccess()
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, error = throwable.toUserMessage()) }
                }
        }
    }

    fun register(onSuccess: () -> Unit) {
        val state = _uiState.value
        when {
            state.name.isBlank() ->
                _uiState.update { it.copy(error = "Spune-ne cum te cheamă.") }

            state.email.isBlank() || !state.email.contains("@") ->
                _uiState.update { it.copy(error = "Emailul nu pare valid.") }

            state.password.length < 8 ->
                _uiState.update { it.copy(error = "Parola trebuie să aibă minim 8 caractere.") }

            else -> viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, error = null) }
                repository.register(state.name.trim(), state.email.trim(), state.password)
                    .onSuccess {
                        _uiState.update { it.copy(isLoading = false) }
                        onSuccess()
                    }
                    .onFailure { throwable ->
                        _uiState.update { it.copy(isLoading = false, error = throwable.toUserMessage()) }
                    }
            }
        }
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is IOException -> "Nu există conexiune la internet."
        is HttpException -> when (code()) {
            400, 401 -> "Email sau parolă incorectă."
            409 -> "Există deja un cont cu acest email."
            else -> "Eroare de server (${code()}). Încearcă din nou."
        }
        else -> "A apărut o eroare. Încearcă din nou."
    }
}
