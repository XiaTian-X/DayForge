package com.dayforge.ui.screens.token

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.data.api.TokenApi
import com.dayforge.data.api.dto.ApiTokenCreateRequest
import com.dayforge.data.api.dto.ApiTokenListResponse
import com.dayforge.data.api.dto.ApiTokenResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TokenViewModel @Inject constructor(
    private val tokenApi: TokenApi
) : ViewModel() {

    private val _tokens = MutableStateFlow<List<ApiTokenListResponse>>(emptyList())
    val tokens: StateFlow<List<ApiTokenListResponse>> = _tokens.asStateFlow()

    private val _createdToken = MutableStateFlow<ApiTokenResponse?>(null)
    val createdToken: StateFlow<ApiTokenResponse?> = _createdToken.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadTokens()
    }

    fun loadTokens() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _tokens.value = tokenApi.listTokens()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load tokens"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createToken(name: String, expiresInDays: Int? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val request = ApiTokenCreateRequest(name = name, expires_in_days = expiresInDays)
                _createdToken.value = tokenApi.createToken(request)
                loadTokens() // Refresh list
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to create token"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteToken(tokenId: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                tokenApi.deleteToken(tokenId)
                // Optimistically remove from list
                _tokens.value = _tokens.value.filter { it.id != tokenId }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to delete token"
                loadTokens() // Reload on error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearCreatedToken() {
        _createdToken.value = null
    }

    fun clearError() {
        _error.value = null
    }
}
