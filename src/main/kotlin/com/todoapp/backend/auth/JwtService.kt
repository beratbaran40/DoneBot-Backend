package com.todoapp.backend.auth

import com.todoapp.backend.config.JwtProperties
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

@Service
class JwtService(private val props: JwtProperties) {

    private val key: SecretKey = Keys.hmacShaKeyFor(props.secret.toByteArray(Charsets.UTF_8))

    fun issueAccessToken(userId: Long): String {
        val now = Instant.now()
        return Jwts.builder()
            .subject(userId.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(props.accessTokenTtlSeconds)))
            .signWith(key)
            .compact()
    }

    fun parseUserId(token: String): Long? = runCatching {
        Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).payload.subject.toLong()
    }.getOrNull()

    fun accessTokenTtlSeconds(): Long = props.accessTokenTtlSeconds
    fun refreshTokenTtlSeconds(): Long = props.refreshTokenTtlSeconds
}
