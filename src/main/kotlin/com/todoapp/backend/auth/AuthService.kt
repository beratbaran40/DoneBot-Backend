package com.todoapp.backend.auth

import com.todoapp.backend.auth.oauth.FacebookAuthService
import com.todoapp.backend.auth.oauth.GoogleAuthService
import com.todoapp.backend.user.UserEntity
import com.todoapp.backend.user.UserRepository
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

class AuthException(msg: String) : RuntimeException(msg)

@Service
class AuthService(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordResets: PasswordResetRepository,
    private val jwtService: JwtService,
    private val passwordEncoder: PasswordEncoder,
    private val google: GoogleAuthService,
    private val facebook: FacebookAuthService,
    private val mailService: MailService,
    @Value("\${app.password-reset.deep-link}") private val resetDeepLink: String,
    @Value("\${app.password-reset.ttl-minutes}") private val resetTtlMinutes: Long,
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)
    private val rng = SecureRandom()

    @Transactional
    fun register(req: RegisterRequest): AuthResponseData {
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
        val hash = creds.passwordHash ?: throw AuthException("Invalid credentials")
        if (!passwordEncoder.matches(req.password, hash)) throw AuthException("Invalid credentials")
        val summary = users.findSummaryById(creds.id) ?: throw AuthException("User not found")
        return issueTokenPair(summary)
    }

    @Transactional
    fun googleLogin(req: OAuthTokenRequest): AuthResponseData {
        val profile = google.verify(req.token) ?: throw AuthException("Invalid Google token")
        return upsertOAuthUser(profile.email, profile.displayName, profile.avatarUrl, "google")
    }

    @Transactional
    fun facebookLogin(req: OAuthTokenRequest): AuthResponseData {
        val profile = facebook.verify(req.token) ?: throw AuthException("Invalid Facebook token")
        return upsertOAuthUser(profile.email, profile.displayName, profile.avatarUrl, "facebook")
    }

    private fun upsertOAuthUser(email: String, displayName: String, avatarUrl: String?, provider: String): AuthResponseData {
        val existing = users.findByEmail(email)
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
        val pair = issueTokenPair(summary)
        return RefreshTokenData(pair.accessToken, pair.refreshToken, pair.expiresIn)
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
            // Social-only account — no password to reset. Still succeed silently.
            log.info("forgotPassword: user {} has no password (oauth-only)", creds.id)
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
        val link = if (resetDeepLink.contains("?")) {
            "$resetDeepLink&token=$rawToken"
        } else {
            "$resetDeepLink?token=$rawToken"
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
