package com.todoapp.backend.user

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class UserData(
    val id: Long,
    val email: String,
    val displayName: String,
    val avatarUrl: String?,
    val emailVerified: Boolean,
    val providers: List<String>,
    val createdAt: String,
)

data class UpdateUserRequest(
    @field:NotBlank @field:Size(min = 1, max = 64) val displayName: String,
)

data class ChangePasswordRequest(
    @field:NotBlank val currentPassword: String,
    @field:NotBlank @field:Size(min = 8, max = 128) val newPassword: String,
)

fun UserEntity.toDto(): UserData = UserData(
    id = id,
    email = email,
    displayName = displayName,
    avatarUrl = if (avatarBytes != null) "/users/$id/avatar" else avatarUrl,
    emailVerified = emailVerified,
    providers = providers,
    createdAt = createdAt.toString(),
)

/**
 * Same UserData shape as the entity-based mapper, but sourced from a UserSummary projection
 * so the avatar BLOB never crosses the wire. Use this on read paths (login response, refresh,
 * any DTO render where the entity wasn't already loaded for a write).
 */
fun UserSummary.toDto(): UserData = UserData(
    id = id,
    email = email,
    displayName = displayName,
    avatarUrl = if (hasAvatar) "/users/$id/avatar" else avatarUrl,
    emailVerified = emailVerified,
    providers = providersCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    createdAt = createdAt.toString(),
)
