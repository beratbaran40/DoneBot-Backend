package com.todoapp.backend.auth

import com.todoapp.backend.auth.oauth.FacebookAuthService
import com.todoapp.backend.auth.oauth.GoogleAuthService
import com.todoapp.backend.user.UserEntity
import com.todoapp.backend.user.UserRepository
import com.todoapp.backend.user.toDto
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

class AuthException(msg: String) : RuntimeException(msg)

@Service
class AuthService(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val jwtService: JwtService,
    private val passwordEncoder: PasswordEncoder,
    private val google: GoogleAuthService,
    private val facebook: FacebookAuthService,
) {
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
        val user = users.findByEmail(req.email) ?: throw AuthException("Invalid credentials")
        val hash = user.passwordHash ?: throw AuthException("Invalid credentials")
        if (!passwordEncoder.matches(req.password, hash)) throw AuthException("Invalid credentials")
        return issueTokenPair(user)
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
        val user = users.findById(record.userId).orElseThrow { AuthException("User not found") }
        val pair = issueTokenPair(user)
        return RefreshTokenData(pair.accessToken, pair.refreshToken, pair.expiresIn)
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

    private fun randomToken(): String {
        val bytes = ByteArray(48).also { rng.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
