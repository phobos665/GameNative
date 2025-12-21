package app.gamenative.gogdl.auth

import android.content.Context
import app.gamenative.gogdl.models.GOGCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber



/**
 * Handles GOG OAuth authentication flow.
 * Manages tokens, refresh logic, and credential storage.
 */
class GOGAuthenticator(private val context: Context) {

    private val tag = "GOGAuthenticator"

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: Flow<AuthState> = _authState

    suspend fun isAuthenticated(): Boolean {
        // TODO: Check if tokens exist and are valid
        Timber.tag(tag).d("isAuthenticated() - stub")
        return false
    }

    fun startAuthFlow(): String {
        // TODO: Build OAuth URL with proper parameters
        Timber.tag(tag).d("startAuthFlow() - stub")
        return "https://auth.gog.com/auth?client_id=..."
    }

    suspend fun handleAuthCallback(authCode: String): Result<GOGCredentials> {
        // TODO: Exchange auth code for tokens
        Timber.tag(tag).d("handleAuthCallback() - stub: $authCode")
        return Result.failure(NotImplementedError("handleAuthCallback not implemented"))
    }

    suspend fun getAccessToken(): String? {
        // TODO: Get token, refresh if expired
        Timber.tag(tag).d("getAccessToken() - stub")
        return null
    }

    suspend fun refreshAccessToken(): Result<GOGCredentials> {
        // TODO: Refresh token logic
        Timber.tag(tag).d("refreshAccessToken() - stub")
        return Result.failure(NotImplementedError("refreshAccessToken not implemented"))
    }


    suspend fun saveCredentials(credentials: GOGCredentials) {
        // TODO: Save to EncryptedSharedPreferences
        Timber.tag(tag).d("saveCredentials() - stub")
    }

    suspend fun loadCredentials(): GOGCredentials? {
        // TODO: Load from EncryptedSharedPreferences
        Timber.tag(tag).d("loadCredentials() - stub")
        return null
    }

    suspend fun clearCredentials() {
        // TODO: Remove all stored credentials
        Timber.tag(tag).d("clearCredentials() - stub")
        _authState.value = AuthState.Unauthenticated
    }

    sealed class AuthState {
        object Unauthenticated : AuthState()
        object Authenticating : AuthState()
        data class Authenticated(val userId: String) : AuthState()
        data class Error(val message: String) : AuthState()
    }
}
