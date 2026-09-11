package com.dayforge.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Authentication token response from login/refresh endpoints.
 */
@Serializable
data class TokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("token_type")
    val tokenType: String = "bearer",
    @SerialName("user_id")
    val userId: String,
    val username: String,
    @SerialName("is_admin")
    val isAdmin: Boolean
)

/**
 * Login request payload.
 */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

/**
 * Token refresh request payload.
 */
@Serializable
data class RefreshRequest(
    @SerialName("refresh_token")
    val refreshToken: String
)
