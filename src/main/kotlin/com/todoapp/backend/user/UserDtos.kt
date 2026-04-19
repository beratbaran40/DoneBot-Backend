package com.todoapp.backend.user

data class UserData(
    val id: Long,
    val email: String,
    val displayName: String,
    val avatarUrl: String?,
    val emailVerified: Boolean,
    val providers: List<String>,
    val createdAt: String,
)

fun UserEntity.toDto(): UserData = UserData(
    id = id,
    email = email,
    displayName = displayName,
    avatarUrl = avatarUrl,
    emailVerified = emailVerified,
    providers = providers,
    createdAt = createdAt.toString(),
)
