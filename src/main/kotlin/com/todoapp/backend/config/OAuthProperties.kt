package com.todoapp.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.oauth.google")
data class GoogleOauthProperties(
    var clientId: String = "",
)

@ConfigurationProperties(prefix = "app.oauth.facebook")
data class FacebookOauthProperties(
    var appId: String = "",
    var appSecret: String = "",
)

@ConfigurationProperties(prefix = "app.firebase")
data class FirebaseProperties(
    var serviceAccountPath: String = "",
)
