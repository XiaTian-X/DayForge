package com.dayforge.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AdminUserResponse(
    val id: Int,
    @SerialName("public_id")
    val publicId: String,
    val username: String,
    val email: String? = null,
    val phone: String? = null,
    @SerialName("is_active")
    val isActive: Boolean,
    @SerialName("is_verified")
    val isVerified: Boolean,
    @SerialName("is_admin")
    val isAdmin: Boolean,
    val status: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String
)

@Serializable
data class AdminUserCreate(
    val username: String,
    val password: String,
    @SerialName("is_admin")
    val isAdmin: Boolean = false
)

@Serializable
data class AdminPasswordReset(
    @SerialName("new_password")
    val newPassword: String
)

@Serializable
data class UserStatusUpdate(
    @SerialName("is_active")
    val isActive: Boolean
)
