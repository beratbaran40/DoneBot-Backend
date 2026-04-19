package com.todoapp.backend.auth.oauth

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.todoapp.backend.config.GoogleOauthProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class GoogleProfile(val email: String, val displayName: String, val avatarUrl: String?)

@Service
class GoogleAuthService(private val props: GoogleOauthProperties) {

    private val log = LoggerFactory.getLogger(GoogleAuthService::class.java)

    private val verifier: GoogleIdTokenVerifier by lazy {
        GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
            .setAudience(listOf(props.clientId))
            .build()
    }

    fun verify(idToken: String): GoogleProfile? {
        if (props.clientId.isBlank()) {
            log.warn("Google verify skipped: app.oauth.google.client-id is not configured")
            return null
        }
        log.info("Google verify: expecting audience={}", props.clientId)
        val token = try {
            verifier.verify(idToken)
        } catch (e: Exception) {
            log.warn("Google verify threw: {}", e.toString())
            return null
        }
        if (token == null) {
            // Parse the JWT payload ourselves to log the actual audience so we can diagnose mismatch.
            val aud = runCatching {
                val parts = idToken.split(".")
                if (parts.size >= 2) {
                    val payloadJson = String(java.util.Base64.getUrlDecoder().decode(parts[1]))
                    Regex("\"aud\"\\s*:\\s*\"([^\"]+)\"").find(payloadJson)?.groupValues?.get(1)
                } else null
            }.getOrNull()
            log.warn("Google verify returned null (signature/audience/expiry invalid). Token aud={}", aud)
            return null
        }
        val payload = token.payload
        val email = payload.email ?: run {
            log.warn("Google verify: token has no email claim")
            return null
        }
        val name = (payload["name"] as? String) ?: email.substringBefore("@")
        val picture = payload["picture"] as? String
        return GoogleProfile(email = email, displayName = name, avatarUrl = picture)
    }
}
