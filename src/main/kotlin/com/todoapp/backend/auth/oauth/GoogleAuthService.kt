package com.todoapp.backend.auth.oauth

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.todoapp.backend.config.GoogleOauthProperties
import org.springframework.stereotype.Service

data class GoogleProfile(val email: String, val displayName: String, val avatarUrl: String?)

@Service
class GoogleAuthService(private val props: GoogleOauthProperties) {

    private val verifier: GoogleIdTokenVerifier by lazy {
        GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
            .setAudience(listOf(props.clientId))
            .build()
    }

    fun verify(idToken: String): GoogleProfile? {
        if (props.clientId.isBlank()) return null
        val token = runCatching { verifier.verify(idToken) }.getOrNull() ?: return null
        val payload = token.payload
        val email = payload.email ?: return null
        val name = (payload["name"] as? String) ?: email.substringBefore("@")
        val picture = payload["picture"] as? String
        return GoogleProfile(email = email, displayName = name, avatarUrl = picture)
    }
}
