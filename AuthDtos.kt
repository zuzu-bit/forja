package com.forja.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forja.app.feature.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    authRepository: AuthRepository
) : ViewModel() {

    /**
     * Se citește O SINGURĂ DATĂ la pornire, ca să alegem ecranul de start:
     * null = încă verificăm, true = are token salvat, false = trebuie login.
     * După aceea, navigația se face manual (login/logout).
     */
    val initialLoggedIn: StateFlow<Boolean?> =
        flow { emit(authRepository.isLoggedIn.first()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
