package com.todoapp.backend.auth

import com.todoapp.backend.common.BaseResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService,
    private val rateLimiter: AuthRateLimiter,
) {

    @PostMapping("/register")
    fun register(
        @Valid @RequestBody req: RegisterRequest,
        request: HttpServletRequest,
    ): BaseResponse<AuthResponseData> {
        rateLimit(request)
        return BaseResponse.ok(authService.register(req))
    }

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody req: LoginRequest,
        request: HttpServletRequest,
    ): BaseResponse<AuthResponseData> {
        rateLimit(request)
        return BaseResponse.ok(authService.login(req))
    }

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
    fun forgotPassword(
        @Valid @RequestBody req: ForgotPasswordRequest,
        request: HttpServletRequest,
    ): BaseResponse<Unit> {
        rateLimit(request)
        authService.forgotPassword(req)
        return BaseResponse.ok(Unit)
    }

    @PostMapping("/reset-password")
    fun resetPassword(
        @Valid @RequestBody req: ResetPasswordRequest,
        request: HttpServletRequest,
    ): BaseResponse<Unit> {
        rateLimit(request)
        authService.resetPassword(req)
        return BaseResponse.ok(Unit)
    }

    @ExceptionHandler(AuthException::class)
    fun handleAuth(ex: AuthException): ResponseEntity<BaseResponse<Nothing>> {
        // "This email uses Google/Facebook" is a conflict, not bad credentials — return 409 so
        // the client's 401 handling (which discards the body) doesn't swallow the errorCode.
        val status = if (ex.errorCode?.startsWith("oauth_account") == true) {
            HttpStatus.CONFLICT
        } else {
            HttpStatus.UNAUTHORIZED
        }
        return ResponseEntity.status(status)
            .body(BaseResponse.error(status.value(), ex.message ?: "Unauthorized", ex.errorCode))
    }

    // Per-IP throttle for the unauthenticated, brute-force-/spam-prone endpoints. A wrong password
    // etc. still surfaces via handleAuth above; this only caps request volume.
    private fun rateLimit(request: HttpServletRequest) {
        when (val gate = rateLimiter.acquire(clientIp(request))) {
            is AuthRateLimiter.Result.Allowed -> Unit
            is AuthRateLimiter.Result.Denied -> throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests. Please retry in ${gate.retryAfterSeconds}s",
            )
        }
    }

    // Prefer the proxy-forwarded client IP; fall back to the socket address. forward-headers-strategy
    // (prod) normalises X-Forwarded-For into remoteAddr, but read the header too for resilience.
    private fun clientIp(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        return if (!forwarded.isNullOrBlank()) {
            forwarded.substringBefore(',').trim()
        } else {
            request.remoteAddr ?: "unknown"
        }
    }
}
