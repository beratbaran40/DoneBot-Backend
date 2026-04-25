package com.todoapp.backend.auth

import com.todoapp.backend.user.UserData
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterRequest(
    @field:Email val email: String,
    @field:NotBlank @field:Size(min = 8, max = 128) val password: String,
    @field:NotBlank val displayName: String,
)

data class LoginRequest(
    @field:Email val email: String,
    @field:NotBlank val password: String,
)

data class RefreshTokenRequest(
    @field:NotBlank val refreshToken: String,
)

data class OAuthTokenRequest(
    @field:NotBlank val token: String,
)

data class AuthResponseData(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: UserData,
)

data class RefreshTokenData(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

data class ForgotPasswordRequest(
    @field:Email val email: String,
)

data class ResetPasswordRequest(
    @field:NotBlank val token: String,
    @field:NotBlank @field:Size(min = 8, max = 128) val newPassword: String,
)
