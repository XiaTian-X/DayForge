package com.dayforge.ui.screens.login

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.EndpointResolver
import com.dayforge.data.api.dto.LoginRequest
import com.dayforge.data.api.dto.TokenResponse
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.AccountLocalStateCleaner
import com.dayforge.domain.service.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Handles login to an administrator-managed DayForge server account. */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val endpointResolver: EndpointResolver,
    private val tokenManager: TokenManager,
    private val syncManager: SyncManager,
    private val habitRepository: HabitRepository,
    private val preferencesManager: PreferencesManager,
    private val accountSessionCoordinator: AccountSessionCoordinator,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()
    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    private val _showSyncPrompt = MutableStateFlow(false)
    val showSyncPrompt: StateFlow<Boolean> = _showSyncPrompt.asStateFlow()
    private val _syncErrorMessage = MutableStateFlow<String?>(null)
    val syncErrorMessage: StateFlow<String?> = _syncErrorMessage.asStateFlow()
    private val _localDataPrompt = MutableStateFlow<LocalDataPrompt?>(null)
    val localDataPrompt: StateFlow<LocalDataPrompt?> = _localDataPrompt.asStateFlow()

    private var pendingLoginResponse: TokenResponse? = null

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()
    private val _serverProtocol = MutableStateFlow("https")
    val serverProtocol: StateFlow<String> = _serverProtocol.asStateFlow()
    private val _serverHostPort = MutableStateFlow("")
    val serverHostPort: StateFlow<String> = _serverHostPort.asStateFlow()
    private val _remoteServerHostPort = MutableStateFlow("")
    val remoteServerHostPort: StateFlow<String> = _remoteServerHostPort.asStateFlow()
    private val _showServerSettings = MutableStateFlow(false)
    val showServerSettings: StateFlow<Boolean> = _showServerSettings.asStateFlow()

    private val _loginEvent = MutableSharedFlow<LoginEvent>()
    val loginEvent: SharedFlow<LoginEvent> = _loginEvent.asSharedFlow()

    init {
        viewModelScope.launch {
            preferencesManager.serverUrl.collect { savedUrl ->
                _serverUrl.value = savedUrl.orEmpty()
                if (savedUrl.isNullOrBlank()) {
                    _serverProtocol.value = "https"
                    _serverHostPort.value = ""
                } else {
                    parseStoredUrl(savedUrl)
                }
            }
        }
        viewModelScope.launch {
            preferencesManager.remoteServerUrl.collect { savedUrl ->
                _remoteServerHostPort.value = savedUrl?.let { url ->
                    runCatching { URI(url) }.getOrNull()?.let { uri ->
                        if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host.orEmpty()
                    }
                }.orEmpty()
            }
        }
    }

    private fun parseStoredUrl(url: String) {
        runCatching { URI(url) }.onSuccess { uri ->
            _serverProtocol.value = uri.scheme ?: "https"
            val host = uri.host.orEmpty()
            _serverHostPort.value = if (uri.port > 0) "$host:${uri.port}" else host
        }.onFailure {
            _serverProtocol.value = "https"
            _serverHostPort.value = ""
        }
    }

    fun onUsernameChange(value: String) {
        _username.value = value
        _errorMessage.value = null
    }

    fun onPasswordChange(value: String) {
        _password.value = value
        _errorMessage.value = null
    }

    fun onLoginClick() {
        if (_isLoading.value) return
        val username = _username.value.trim()
        val password = _password.value
        if (username.isEmpty()) {
            _errorMessage.value = context.getString(R.string.login_error_enter_username)
            return
        }
        if (password.isEmpty()) {
            _errorMessage.value = context.getString(R.string.login_error_enter_password)
            return
        }

        _isLoading.value = true
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                persistServerSelection()
                endpointResolver.resolve()
                val health = authApi.health()
                check(health.status == "ok") { "Server is not ready" }
                val response = authApi.login(LoginRequest(username, password))

                val needsLocalDataDecision = accountSessionCoordinator.exclusive {
                    val localDataOwner = tokenManager.syncAccountId.first()
                    val accountChanged = localDataOwner != response.userId
                    if (accountChanged && syncManager.hasLocalData()) {
                        pendingLoginResponse = response
                        _localDataPrompt.value = LocalDataPrompt(
                            accountName = response.username,
                            canMerge = localDataOwner == null
                        )
                        true
                    } else {
                        if (accountChanged) clearLocalAccountData()
                        saveCredentials(response)
                        false
                    }
                }
                if (!needsLocalDataDecision) _showSyncPrompt.value = true
            } catch (e: Exception) {
                _errorMessage.value = parseLoginError(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onSyncConfirm() {
        if (_isLoading.value) return
        _isLoading.value = true
        viewModelScope.launch {
            _showSyncPrompt.value = false
            _syncErrorMessage.value = null
            try {
                val result = syncManager.sync()
                if (result.isSuccess) {
                    _loginEvent.emit(LoginEvent.Success)
                } else {
                    val detail = result.exceptionOrNull()?.message
                        ?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.common_error)
                    _syncErrorMessage.value = context.getString(R.string.login_sync_failed, detail)
                    _showSyncPrompt.value = true
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onSyncSkip() {
        viewModelScope.launch {
            _showSyncPrompt.value = false
            _syncErrorMessage.value = null
            _loginEvent.emit(LoginEvent.Success)
        }
    }

    /** Assign legacy, previously unowned local changes to the newly authenticated account. */
    fun onMergeLocalData() {
        val prompt = _localDataPrompt.value
        if (prompt?.canMerge != true) return
        continuePendingLogin(discardLocalData = false)
    }

    /** Explicitly discard pending local changes before activating the new account. */
    fun onDiscardLocalData() {
        if (_localDataPrompt.value == null) return
        continuePendingLogin(discardLocalData = true)
    }

    /** Keep the current local account and its data; the new credentials are not stored. */
    fun onCancelLocalDataDecision() {
        if (_isLoading.value) return
        pendingLoginResponse = null
        _localDataPrompt.value = null
    }

    private fun continuePendingLogin(discardLocalData: Boolean) {
        if (_isLoading.value) return
        _isLoading.value = true
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                accountSessionCoordinator.exclusive {
                    val response = checkNotNull(pendingLoginResponse) {
                        "Login session expired"
                    }
                    if (discardLocalData) {
                        clearLocalAccountData()
                    } else {
                        check(tokenManager.syncAccountId.first() == null) {
                            "Local data is already assigned to another account"
                        }
                    }
                    saveCredentials(response)
                    pendingLoginResponse = null
                    _localDataPrompt.value = null
                }
                _syncErrorMessage.value = null
                _showSyncPrompt.value = true
            } catch (e: Exception) {
                _errorMessage.value = parseLoginError(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun clearLocalAccountData() {
        // Invalidate any previous session before its cache is removed. If a later
        // persistence step fails, the app stays safely logged out instead of exposing
        // an empty cache under stale credentials.
        tokenManager.clearAuthenticationTokens()
        AccountLocalStateCleaner.clear(context) {
            habitRepository.clearAllData(context)
            preferencesManager.clearAccountScopedPreferences()
        }
        preferencesManager.clearLastSyncTimestamp()
        tokenManager.clearSyncState()
        syncManager.resetProgress()
    }

    private suspend fun saveCredentials(response: TokenResponse) {
        // Credentials are written last so startup/background sync can never observe
        // a new account together with a previous account's cache.
        tokenManager.saveTokens(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            email = response.username,
            userId = response.userId,
            isAdmin = response.isAdmin
        )
    }

    private fun parseLoginError(error: Exception): String = when {
        error.message?.contains("401") == true -> context.getString(R.string.login_error_wrong_credentials)
        error.message?.contains("403") == true -> context.getString(R.string.login_error_wrong_credentials)
        error.message?.contains("address", ignoreCase = true) == true -> context.getString(R.string.server_error_invalid_address)
        error.message?.contains("network", ignoreCase = true) == true -> context.getString(R.string.login_error_network)
        else -> error.message ?: context.getString(R.string.common_error)
    }

    fun toggleServerSettings() {
        _showServerSettings.value = !_showServerSettings.value
    }

    fun onServerProtocolChange(protocol: String) {
        _serverProtocol.value = protocol
        _errorMessage.value = null
    }

    fun onServerHostPortChange(hostPort: String) {
        _serverHostPort.value = hostPort
        _errorMessage.value = null
    }

    fun onRemoteServerHostPortChange(hostPort: String) {
        _remoteServerHostPort.value = hostPort
        _errorMessage.value = null
    }

    fun saveServerUrl() {
        viewModelScope.launch {
            runCatching { persistServerSelection() }
                .onFailure { _errorMessage.value = context.getString(R.string.server_error_invalid_address) }
        }
    }

    private suspend fun persistServerSelection() {
        val hostPort = _serverHostPort.value.trim()
        if (hostPort.isEmpty()) {
            preferencesManager.setServerUrl(null)
            _serverUrl.value = ""
        } else {
            val url = validatedServerUrl(_serverProtocol.value, hostPort)
            preferencesManager.setServerUrl(url)
            _serverUrl.value = url
        }

        val remoteHostPort = _remoteServerHostPort.value.trim()
        val remoteUrl = if (remoteHostPort.isEmpty()) null
        else validatedServerUrl("https", remoteHostPort)
        preferencesManager.setRemoteServerUrl(remoteUrl)
    }

    private fun validatedServerUrl(protocol: String, hostPort: String): String {
        require(Regex("^[a-zA-Z0-9.-]+(:[0-9]{1,5})?$").matches(hostPort)) {
            "Invalid server address"
        }
        val url = "$protocol://$hostPort"
        val uri = URI(url)
        require(uri.host != null && uri.path.isNullOrEmpty() && uri.userInfo == null) {
            "Invalid server address"
        }
        if (uri.port > 65535) throw IllegalArgumentException("Invalid server address")
        return url
    }

    fun resetServerUrl() {
        viewModelScope.launch {
            preferencesManager.setServerUrl(null)
            preferencesManager.setRemoteServerUrl(null)
            _serverUrl.value = ""
            _serverProtocol.value = "https"
            _serverHostPort.value = ""
            _remoteServerHostPort.value = ""
        }
    }

    sealed class LoginEvent {
        data object Success : LoginEvent()
    }

    data class LocalDataPrompt(
        val accountName: String,
        val canMerge: Boolean
    )
}
