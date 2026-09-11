package com.dayforge.data.api

import com.dayforge.data.api.dto.AdminPasswordReset
import com.dayforge.data.api.dto.AdminUserResponse
import com.dayforge.data.api.dto.AdminUserCreate
import com.dayforge.data.api.dto.ApiTokenCreateRequest
import com.dayforge.data.api.dto.ApiTokenListResponse
import com.dayforge.data.api.dto.ApiTokenResponse
import com.dayforge.data.api.dto.UserStatusUpdate
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface AdminApi {
    @POST("admin/users")
    suspend fun createUser(@Body request: AdminUserCreate): AdminUserResponse

    @GET("admin/users")
    suspend fun listUsers(): List<AdminUserResponse>

    @POST("admin/users/{user_id}/reset-password")
    suspend fun resetPassword(
        @Path("user_id") userId: Int,
        @Body request: AdminPasswordReset
    )

    @PUT("admin/users/{user_id}/status")
    suspend fun updateUserStatus(
        @Path("user_id") userId: Int,
        @Body request: UserStatusUpdate
    ): AdminUserResponse

    @GET("admin/users/{user_id}/tokens")
    suspend fun listUserTokens(@Path("user_id") userId: Int): List<ApiTokenListResponse>

    @POST("admin/users/{user_id}/tokens")
    suspend fun createUserToken(
        @Path("user_id") userId: Int,
        @Body request: ApiTokenCreateRequest
    ): ApiTokenResponse

    @DELETE("admin/users/{user_id}/tokens/{token_id}")
    suspend fun deleteUserToken(
        @Path("user_id") userId: Int,
        @Path("token_id") tokenId: Int
    )
}
