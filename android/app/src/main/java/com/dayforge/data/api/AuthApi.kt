package com.dayforge.data.api

import com.dayforge.data.api.dto.LoginRequest
import com.dayforge.data.api.dto.RefreshRequest
import com.dayforge.data.api.dto.TokenResponse
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit interface for authentication API endpoints.
 */
interface AuthApi {
    /**
     * Login with username and password.
     * Returns access and refresh tokens.
     */
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): TokenResponse

    /**
     * Refresh an expired access token.
     * Uses the refresh token to obtain new tokens.
     */
    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: RefreshRequest): TokenResponse

    @GET("/health")
    suspend fun health(): HealthResponse
}

@Serializable
data class HealthResponse(val status: String)
