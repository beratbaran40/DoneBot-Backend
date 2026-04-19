package com.todoapp.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.jwt")
data class JwtProperties(
    var secret: String = "",
    var accessTokenTtlSeconds: Long = 3600,
    var refreshTokenTtlSeconds: Long = 2_592_000,
)
