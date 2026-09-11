package com.dayforge.data.api

import com.dayforge.data.api.dto.ApiTokenCreateRequest
import com.dayforge.data.api.dto.ApiTokenListResponse
import com.dayforge.data.api.dto.ApiTokenResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface TokenApi {
    @POST("auth/tokens")
    suspend fun createToken(@Body request: ApiTokenCreateRequest): ApiTokenResponse

    @GET("auth/tokens")
    suspend fun listTokens(): List<ApiTokenListResponse>

    @DELETE("auth/tokens/{token_id}")
    suspend fun deleteToken(@Path("token_id") tokenId: Int)
}
