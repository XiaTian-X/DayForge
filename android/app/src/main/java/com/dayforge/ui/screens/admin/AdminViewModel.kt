package com.dayforge.ui.screens.admin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.api.AdminApi
import com.dayforge.data.api.dto.AdminPasswordReset
import com.dayforge.data.api.dto.AdminUserResponse
import com.dayforge.data.api.dto.AdminUserCreate
import com.dayforge.data.api.dto.ApiTokenCreateRequest
import com.dayforge.data.api.dto.ApiTokenListResponse
import com.dayforge.data.api.dto.ApiTokenResponse
import com.dayforge.data.api.dto.UserStatusUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val adminApi: AdminApi,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _users = MutableStateFlow<List<AdminUserResponse>>(emptyList())
    val users: StateFlow<List<AdminUserResponse>> = _users.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _resetPasswordDialog = MutableStateFlow<AdminUserResponse?>(null)
    val resetPasswordDialog: StateFlow<AdminUserResponse?> = _resetPasswordDialog.asStateFlow()

    private val _resetPasswordInput = MutableStateFlow("")
    val resetPasswordInput: StateFlow<String> = _resetPasswordInput.asStateFlow()

    // Token management state
    private val _selectedUser = MutableStateFlow<AdminUserResponse?>(null)
    val selectedUser: StateFlow<AdminUserResponse?> = _selectedUser.asStateFlow()

    private val _userTokens = MutableStateFlow<List<ApiTokenListResponse>>(emptyList())
    val userTokens: StateFlow<List<ApiTokenListResponse>> = _userTokens.asStateFlow()

    private val _tokensLoading = MutableStateFlow(false)
    val tokensLoading: StateFlow<Boolean> = _tokensLoading.asStateFlow()

    private val _createdToken = MutableStateFlow<ApiTokenResponse?>(null)
    val createdToken: StateFlow<ApiTokenResponse?> = _createdToken.asStateFlow()

    private val _event = MutableSharedFlow<AdminEvent>()
    val event: SharedFlow<AdminEvent> = _event.asSharedFlow()

    init {
        loadUsers()
    }

    fun loadUsers() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                _users.value = adminApi.listUsers()
            } catch (e: Exception) {
                _errorMessage.value = parseError(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun createUser(username: String, password: String, isAdmin: Boolean) {
        val normalizedUsername = username.trim()
        if (!Regex("^[a-zA-Z0-9_]{3,32}$").matches(normalizedUsername)) {
            _errorMessage.value = context.getString(R.string.admin_error_invalid_username)
            return
        }
        if (password.length < 8) {
            _errorMessage.value = context.getString(R.string.admin_error_password_min_length)
            return
        }
        viewModelScope.launch {
            try {
                adminApi.createUser(AdminUserCreate(normalizedUsername, password, isAdmin))
                loadUsers()
                _event.emit(AdminEvent.UserCreated(normalizedUsername))
            } catch (e: Exception) {
                _errorMessage.value = parseError(e)
            }
        }
    }

    fun toggleUserStatus(user: AdminUserResponse) {
        viewModelScope.launch {
            try {
                adminApi.updateUserStatus(user.id, UserStatusUpdate(isActive = !user.isActive))
                loadUsers()
            } catch (e: Exception) {
                _errorMessage.value = parseError(e)
            }
        }
    }

    fun showResetPasswordDialog(user: AdminUserResponse) {
        _resetPasswordDialog.value = user
        _resetPasswordInput.value = ""
    }

    fun dismissResetPasswordDialog() {
        _resetPasswordDialog.value = null
        _resetPasswordInput.value = ""
    }

    fun onResetPasswordInputChange(password: String) {
        _resetPasswordInput.value = password
    }

    fun confirmResetPassword() {
        val user = _resetPasswordDialog.value ?: return
        val newPassword = _resetPasswordInput.value

        if (newPassword.length < 8) {
            _errorMessage.value = context.getString(R.string.admin_error_password_min_length)
            return
        }

        viewModelScope.launch {
            try {
                adminApi.resetPassword(user.id, AdminPasswordReset(newPassword))
                _resetPasswordDialog.value = null
                _resetPasswordInput.value = ""
                _event.emit(AdminEvent.PasswordResetSuccess(user.username))
            } catch (e: Exception) {
                _errorMessage.value = parseError(e)
            }
        }
    }

    // Token management

    fun selectUser(user: AdminUserResponse) {
        if (_selectedUser.value?.id == user.id) {
            _selectedUser.value = null
            _userTokens.value = emptyList()
        } else {
            _selectedUser.value = user
            loadUserTokens(user.id)
        }
    }

    fun dismissSelectedUser() {
        _selectedUser.value = null
        _userTokens.value = emptyList()
    }

    fun loadUserTokens(userId: Int) {
        viewModelScope.launch {
            _tokensLoading.value = true
            try {
                _userTokens.value = adminApi.listUserTokens(userId)
            } catch (e: Exception) {
                _errorMessage.value = parseError(e)
            } finally {
                _tokensLoading.value = false
            }
        }
    }

    fun createUserToken(name: String, expiresInDays: Int?) {
        val user = _selectedUser.value ?: return
        viewModelScope.launch {
            try {
                val token = adminApi.createUserToken(
                    user.id,
                    ApiTokenCreateRequest(name = name, expires_in_days = expiresInDays)
                )
                _createdToken.value = token
                loadUserTokens(user.id)
            } catch (e: Exception) {
                _errorMessage.value = parseError(e)
            }
        }
    }

    fun deleteUserToken(tokenId: Int) {
        val user = _selectedUser.value ?: return
        viewModelScope.launch {
            try {
                adminApi.deleteUserToken(user.id, tokenId)
                // Optimistically remove from list
                _userTokens.value = _userTokens.value.filter { it.id != tokenId }
            } catch (e: Exception) {
                _errorMessage.value = parseError(e)
                loadUserTokens(user.id) // Reload on error
            }
        }
    }

    fun clearCreatedToken() {
        _createdToken.value = null
    }

    private fun parseError(e: Exception): String {
        return when {
            e.message?.contains("403") == true -> context.getString(R.string.admin_error_forbidden)
            e.message?.contains("404") == true -> context.getString(R.string.admin_error_user_not_found)
            e.message?.contains("409") == true -> context.getString(R.string.admin_error_username_exists)
            e.message?.contains("network", ignoreCase = true) == true -> context.getString(R.string.login_error_network)
            else -> context.getString(R.string.admin_error_unknown, e.message ?: "")
        }
    }

    sealed class AdminEvent {
        data class PasswordResetSuccess(val username: String) : AdminEvent()
        data class UserCreated(val username: String) : AdminEvent()
    }
}
