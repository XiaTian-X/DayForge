package com.dayforge.data.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ApiTokenCreateRequest(
    val name: String,
    val expires_in_days: Int? = null
)

@Serializable
data class ApiTokenResponse(
    val id: Int,
    val name: String,
    val prefix: String,
    val token: String? = null,
    val last_used_at: String? = null,
    val created_at: String,
    val expires_at: String? = null
)

@Serializable
data class ApiTokenListResponse(
    val id: Int,
    val name: String,
    val prefix: String,
    val last_used_at: String? = null,
    val created_at: String,
    val expires_at: String? = null
)
