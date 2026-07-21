package com.forja.app.feature.auth

import com.forja.app.core.data.TokenManager
import com.forja.app.core.data.db.UserDao
import com.forja.app.core.data.db.UserEntity
import com.forja.app.core.network.ApiService
import com.forja.app.core.network.dto.AuthResponse
import com.forja.app.core.network.dto.LoginRequest
import com.forja.app.core.network.dto.RegisterRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val tokenManager: TokenManager,
    private val userDao: UserDao
) {
    /** true dacă există un token JWT salvat local. */
    val isLoggedIn: Flow<Boolean> = tokenManager.token.map { !it.isNullOrBlank() }

    /** Utilizatorul curent, din baza locală (funcționează și offline). */
    val currentUser: Flow<UserEntity?> = userDao.currentUser()

    suspend fun login(email: String, password: String): Result<Unit> = runCatching {
        val response = api.login(LoginRequest(email = email, password = password))
        persistSession(response)
    }

    suspend fun register(name: String, email: String, password: String): Result<Unit> = runCatching {
        val response = api.register(RegisterRequest(name = name, email = email, password = password))
        persistSession(response)
    }

    suspend fun logout() {
        tokenManager.clear()
        userDao.clear()
    }

    private suspend fun persistSession(response: AuthResponse) {
        tokenManager.saveToken(response.token)
        userDao.clear()
        userDao.upsert(
            UserEntity(
                id = response.user.id,
                name = response.user.name.orEmpty(),
                email = response.user.email
            )
        )
    }
}
