package com.todoapp.backend.auth

import com.todoapp.backend.common.BaseResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/register")
    fun register(@Valid @RequestBody req: RegisterRequest): BaseResponse<AuthResponseData> =
        BaseResponse.ok(authService.register(req))

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): BaseResponse<AuthResponseData> =
        BaseResponse.ok(authService.login(req))

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody req: RefreshTokenRequest): BaseResponse<RefreshTokenData> =
        BaseResponse.ok(authService.refresh(req))

    @PostMapping("/google")
    fun google(@Valid @RequestBody req: OAuthTokenRequest): BaseResponse<AuthResponseData> =
        BaseResponse.ok(authService.googleLogin(req))

    @PostMapping("/facebook")
    fun facebook(@Valid @RequestBody req: OAuthTokenRequest): BaseResponse<AuthResponseData> =
        BaseResponse.ok(authService.facebookLogin(req))

    @PostMapping("/forgot-password")
    fun forgotPassword(@Valid @RequestBody req: ForgotPasswordRequest): BaseResponse<Unit> {
        authService.forgotPassword(req)
        return BaseResponse.ok(Unit)
    }

    @PostMapping("/reset-password")
    fun resetPassword(@Valid @RequestBody req: ResetPasswordRequest): BaseResponse<Unit> {
        authService.resetPassword(req)
        return BaseResponse.ok(Unit)
    }

    @ExceptionHandler(AuthException::class)
    fun handleAuth(ex: AuthException): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(BaseResponse.error(401, ex.message ?: "Unauthorized"))
}
