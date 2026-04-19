package com.todoapp.backend.auth.oauth

import com.todoapp.backend.config.FacebookOauthProperties
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

data class FacebookProfile(val email: String, val displayName: String, val avatarUrl: String?)

@Service
class FacebookAuthService(private val props: FacebookOauthProperties) {

    private val client = RestClient.create("https://graph.facebook.com")

    fun verify(accessToken: String): FacebookProfile? {
        if (props.appId.isBlank() || props.appSecret.isBlank()) return null

        // 1. Validate the token belongs to OUR app via debug_token
        val appAccessToken = "${props.appId}|${props.appSecret}"
        val debug = runCatching {
            client.get()
                .uri("/debug_token?input_token={t}&access_token={a}", accessToken, appAccessToken)
                .retrieve()
                .body<Map<String, Any>>()
        }.getOrNull() ?: return null
        val data = debug["data"] as? Map<*, *> ?: return null
        if (data["app_id"]?.toString() != props.appId) return null
        if (data["is_valid"] != true) return null

        // 2. Fetch profile
        val me = runCatching {
            client.get()
                .uri("/me?fields=id,email,name,picture.type(large)&access_token={t}", accessToken)
                .retrieve()
                .body<Map<String, Any>>()
        }.getOrNull() ?: return null

        val email = me["email"] as? String ?: return null
        val name = me["name"] as? String ?: email.substringBefore("@")
        val picture = ((me["picture"] as? Map<*, *>)?.get("data") as? Map<*, *>)?.get("url") as? String
        return FacebookProfile(email = email, displayName = name, avatarUrl = picture)
    }
}
