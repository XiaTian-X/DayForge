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
    @param:ApplicationContext private val context: Context
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
        userOperation { _users.value = adminApi.listUsers() }
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
        userOperation {
            adminApi.createUser(AdminUserCreate(normalizedUsername, password, isAdmin))
            _event.emit(AdminEvent.UserCreated(normalizedUsername))
            _users.value = adminApi.listUsers()
        }
    }

    fun toggleUserStatus(user: AdminUserResponse) {
        userOperation {
            adminApi.updateUserStatus(user.id, UserStatusUpdate(isActive = !user.isActive))
            _users.value = adminApi.listUsers()
        }
    }

    fun showResetPasswordDialog(user: AdminUserResponse) {
        if (_isLoading.value) return
        _resetPasswordDialog.value = user
        _resetPasswordInput.value = ""
    }

    fun dismissResetPasswordDialog() {
        if (_isLoading.value) return
        _resetPasswordDialog.value = null
        _resetPasswordInput.value = ""
    }

    fun onResetPasswordInputChange(password: String) {
        if (_isLoading.value) return
        _resetPasswordInput.value = password
    }

    fun confirmResetPassword() {
        val user = _resetPasswordDialog.value ?: return
        val newPassword = _resetPasswordInput.value

        if (newPassword.length < 8) {
            _errorMessage.value = context.getString(R.string.admin_error_password_min_length)
            return
        }

        userOperation {
            adminApi.resetPassword(user.id, AdminPasswordReset(newPassword))
            _resetPasswordDialog.value = null
            _resetPasswordInput.value = ""
            _event.emit(AdminEvent.PasswordResetSuccess(user.username))
        }
    }

    // Token management

    fun selectUser(user: AdminUserResponse) {
        if (_isLoading.value || _tokensLoading.value) return
        if (_selectedUser.value?.id == user.id) {
            _selectedUser.value = null
            _userTokens.value = emptyList()
        } else {
            _selectedUser.value = user
            loadUserTokens(user.id)
        }
    }

    fun dismissSelectedUser() {
        if (_tokensLoading.value) return
        _selectedUser.value = null
        _userTokens.value = emptyList()
    }

    fun loadUserTokens(userId: Int) {
        tokenOperation {
            val tokens = adminApi.listUserTokens(userId)
            if (_selectedUser.value?.id == userId) _userTokens.value = tokens
        }
    }

    fun createUserToken(name: String, expiresInDays: Int?) {
        val user = _selectedUser.value ?: return
        tokenOperation {
            val token = adminApi.createUserToken(
                user.id,
                ApiTokenCreateRequest(name = name, expires_in_days = expiresInDays)
            )
            _createdToken.value = token
            _userTokens.value = adminApi.listUserTokens(user.id)
        }
    }

    fun deleteUserToken(tokenId: Int) {
        val user = _selectedUser.value ?: return
        tokenOperation {
            try {
                adminApi.deleteUserToken(user.id, tokenId)
                _userTokens.value = _userTokens.value.filter { it.id != tokenId }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                try { _userTokens.value = adminApi.listUserTokens(user.id) }
                catch (refreshError: Exception) {
                    if (refreshError is kotlinx.coroutines.CancellationException) throw refreshError
                    // Preserve the original visible failure if reconciliation is also unavailable.
                }
                throw error
            }
        }
    }

    fun clearCreatedToken() {
        _createdToken.value = null
    }

    private fun userOperation(action: suspend () -> Unit) = operation(_isLoading, action)
    private fun tokenOperation(action: suspend () -> Unit) = operation(_tokensLoading, action)

    private fun operation(busy: MutableStateFlow<Boolean>, action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true // Admission and feedback precede coroutine dispatch.
        _errorMessage.value = null
        viewModelScope.launch {
            try { action() }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _errorMessage.value = parseError(error)
            } finally { busy.value = false }
        }
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
