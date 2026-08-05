package com.todoapp.backend.auth

import com.todoapp.backend.auth.oauth.GoogleAuthService
import com.todoapp.backend.settings.AppSetting
import com.todoapp.backend.settings.AppSettingsService
import com.todoapp.backend.user.UserEntity
import com.todoapp.backend.user.UserRepository
import com.todoapp.backend.user.UserStatus
import com.todoapp.backend.user.UserSummary
import com.todoapp.backend.user.toDto
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

class AuthException(msg: String, val errorCode: String? = null) : RuntimeException(msg)

@Service
class AuthService(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordResets: PasswordResetRepository,
    private val jwtService: JwtService,
    private val passwordEncoder: PasswordEncoder,
    private val google: GoogleAuthService,
    private val mailService: MailService,
    private val settings: AppSettingsService,
    @Value("\${app.password-reset.deep-link}") private val resetDeepLink: String,
    @Value("\${app.password-reset.web-link:}") private val resetWebLink: String,
    @Value("\${app.password-reset.ttl-minutes}") private val resetTtlMinutes: Long,
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)
    private val rng = SecureRandom()

    @Transactional
    fun register(req: RegisterRequest): AuthResponseData {
        requireRegistrationOpen()
        if (users.existsByEmail(req.email)) throw AuthException("Email already registered")
        val user = users.save(
            UserEntity(
                email = req.email,
                displayName = req.displayName,
                passwordHash = passwordEncoder.encode(req.password),
            )
        )
        return issueTokenPair(user)
    }

    @Transactional
    fun login(req: LoginRequest): AuthResponseData {
        val creds = users.findCredentialsByEmail(req.email) ?: throw AuthException("Invalid credentials")
        val hash = creds.passwordHash ?: throw AuthException(
            "This account uses social sign-in. Please continue with your social provider.",
            errorCode = oauthAccountErrorCode(creds.providersCsv),
        )
        if (!passwordEncoder.matches(req.password, hash)) throw AuthException("Invalid credentials")
        requireNotSuspended(creds.status)
        val summary = users.findSummaryById(creds.id) ?: throw AuthException("User not found")
        return issueTokenPair(summary)
    }

    /**
     * Suspension is enforced at the three points where a session is created or extended, and nowhere
     * else. It deliberately does NOT check on every API request: that would add a database read to the
     * hot path of every call the app makes, which on a serverless Postgres is real cost, to close a
     * window that closes itself.
     *
     * Suspending revokes every refresh token, so an already-issued access token is the only thing left
     * — and it expires within the one-hour TTL. Shortening that TTL to narrow the window would multiply
     * refresh traffic for every user in order to speed up a rare moderation action; the hour is the
     * better trade, and it is a bounded, documented one rather than an accident.
     */
    private fun requireNotSuspended(status: String) {
        if (status == UserStatus.SUSPENDED.name) {
            // An AuthException maps to 401 through AuthController, which is what the Android client's
            // OkHttp Authenticator already knows how to handle. Inventing a new status code here would
            // put the live app on an untested error path.
            throw AuthException("This account has been suspended.", errorCode = "account_suspended")
        }
    }

    private fun requireRegistrationOpen() {
        if (!settings.isEnabled(AppSetting.REGISTRATION_ENABLED)) {
            throw AuthException("Registration is temporarily closed.", errorCode = "registration_closed")
        }
    }

    /** Builds a stable client-facing code naming the provider, e.g. "oauth_account_google". */
    private fun oauthAccountErrorCode(providersCsv: String): String {
        val provider = providersCsv.split(",")
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && it != "email" }
        return if (provider != null) "oauth_account_$provider" else "oauth_account"
    }

    @Transactional
    fun googleLogin(req: OAuthTokenRequest): AuthResponseData {
        val profile = google.verify(req.token) ?: throw AuthException("Invalid Google token")
        return upsertOAuthUser(profile.email, profile.displayName, profile.avatarUrl, "google")
    }

    private fun upsertOAuthUser(email: String, displayName: String, avatarUrl: String?, provider: String): AuthResponseData {
        val existing = users.findByEmail(email)
        // Google sign-in creates accounts too, so gating only /auth/register would be a half-switch:
        // registration would look closed while the most common signup path stayed wide open.
        if (existing == null) requireRegistrationOpen()
        existing?.let { requireNotSuspended(it.status) }
        val user = if (existing != null) {
            if (provider !in existing.providers) {
                existing.providersCsv = (existing.providers + provider).joinToString(",")
            }
            if (existing.avatarUrl == null && avatarUrl != null) existing.avatarUrl = avatarUrl
            existing.emailVerified = true
            users.save(existing)
        } else {
            users.save(
                UserEntity(
                    email = email,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    emailVerified = true,
                    providersCsv = provider,
                )
            )
        }
        return issueTokenPair(user)
    }

    @Transactional
    fun refresh(req: RefreshTokenRequest): RefreshTokenData {
        val hash = sha256(req.refreshToken)
        val record = refreshTokens.findByTokenHash(hash) ?: throw AuthException("Invalid refresh token")
        if (record.revoked || record.expiresAt.isBefore(Instant.now())) throw AuthException("Refresh token expired")
        record.revoked = true
        refreshTokens.save(record)
        val summary = users.findSummaryById(record.userId) ?: throw AuthException("User not found")
        // The load-bearing check: suspending revokes every refresh token, but a token issued moments
        // earlier would still be in flight. Refusing here is what makes suspension take effect within
        // one access-token lifetime rather than thirty days.
        requireNotSuspended(summary.status)
        val pair = issueTokenPair(summary)
        return RefreshTokenData(pair.accessToken, pair.refreshToken, pair.expiresIn)
    }

    /**
     * Server-side sign-out: revoke the caller's refresh token so it can never mint another access
     * token. Idempotent — an unknown or already-revoked token is a no-op (still 200), so a client
     * logging out with a stale/rotated token still succeeds. Access JWTs stay valid until their short
     * TTL expires; killing them instantly would need a blocklist, which we intentionally don't add.
     */
    @Transactional
    fun logout(req: RefreshTokenRequest) {
        val hash = sha256(req.refreshToken)
        val record = refreshTokens.findByTokenHash(hash) ?: return
        if (!record.revoked) {
            record.revoked = true
            refreshTokens.save(record)
        }
    }

    @Transactional
    fun forgotPassword(req: ForgotPasswordRequest) {
        val creds = users.findCredentialsByEmail(req.email.trim())
        if (creds == null) {
            // Do not reveal account existence.
            log.info("forgotPassword: no account for {}", req.email)
            return
        }
        if (creds.passwordHash == null) {
            // Social-only account — no password to reset. Send a helpful notice to the (real)
            // inbox owner instead of silently doing nothing; the API response stays a generic
            // 200 so the requester learns nothing (no account enumeration).
            log.info("forgotPassword: user {} has no password (oauth-only)", creds.id)
            try {
                mailService.sendOAuthAccountNotice(creds.email, creds.displayName, creds.providersCsv)
            } catch (ex: Exception) {
                log.error("Failed to send oauth-account notice to {}", creds.email, ex)
            }
            return
        }
        // Invalidate previous unused tokens for this user.
        passwordResets.deleteAllByUserId(creds.id)
        val rawToken = randomToken()
        passwordResets.save(
            PasswordResetEntity(
                userId = creds.id,
                tokenHash = sha256(rawToken),
                expiresAt = Instant.now().plus(Duration.ofMinutes(resetTtlMinutes)),
            )
        )
        // Prefer the HTTPS landing page (web-link) over the raw custom-scheme deep link: email
        // clients (Gmail, Outlook, …) refuse to make `todoapp://…` clickable, so the button looked
        // dead. The landing page is an https URL they DO linkify, and it relaunches the app via the
        // deep link from inside a real browser (where custom schemes work). Falls back to the deep
        // link when no web-link is configured (dev / [MAIL:DEV] logging path).
        val linkBase = resetWebLink.ifBlank { resetDeepLink }
        val link = if (linkBase.contains("?")) {
            "$linkBase&token=$rawToken"
        } else {
            "$linkBase?token=$rawToken"
        }
        try {
            mailService.sendPasswordReset(creds.email, creds.displayName, link)
        } catch (ex: Exception) {
            log.error("Failed to send reset email to {}", creds.email, ex)
        }
    }

    @Transactional
    fun resetPassword(req: ResetPasswordRequest) {
        val record = passwordResets.findByTokenHash(sha256(req.token))
            ?: throw AuthException("Invalid or expired reset link")
        if (record.usedAt != null) throw AuthException("Reset link already used")
        if (record.expiresAt.isBefore(Instant.now())) throw AuthException("Reset link expired")
        val user = users.findById(record.userId).orElseThrow { AuthException("User not found") }
        user.passwordHash = passwordEncoder.encode(req.newPassword)
        users.save(user)
        record.usedAt = Instant.now()
        passwordResets.save(record)
        refreshTokens.revokeAllByUserId(user.id)
    }

    private fun issueTokenPair(user: UserEntity): AuthResponseData {
        val access = jwtService.issueAccessToken(user.id)
        val refreshRaw = randomToken()
        refreshTokens.save(
            RefreshTokenEntity(
                userId = user.id,
                tokenHash = sha256(refreshRaw),
                expiresAt = Instant.now().plusSeconds(jwtService.refreshTokenTtlSeconds()),
            )
        )
        return AuthResponseData(
            accessToken = access,
            refreshToken = refreshRaw,
            expiresIn = jwtService.accessTokenTtlSeconds(),
            user = user.toDto(),
        )
    }

    /** Lighter token-pair issuance using a UserSummary projection — avoids loading the avatar BLOB. */
    private fun issueTokenPair(user: UserSummary): AuthResponseData {
        val access = jwtService.issueAccessToken(user.id)
        val refreshRaw = randomToken()
        refreshTokens.save(
            RefreshTokenEntity(
                userId = user.id,
                tokenHash = sha256(refreshRaw),
                expiresAt = Instant.now().plusSeconds(jwtService.refreshTokenTtlSeconds()),
            )
        )
        return AuthResponseData(
            accessToken = access,
            refreshToken = refreshRaw,
            expiresIn = jwtService.accessTokenTtlSeconds(),
            user = user.toDto(),
        )
    }

    private fun randomToken(): String {
        val bytes = ByteArray(48).also { rng.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
