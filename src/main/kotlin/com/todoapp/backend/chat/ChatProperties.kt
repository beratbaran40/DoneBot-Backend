package com.todoapp.backend.chat

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Vertex AI / chat configuration. Driven by `app.vertex.*` keys in
 * application.properties; production values come from Render env vars.
 *
 * When [projectId] is blank the chat endpoint short-circuits to 503 so the
 * rest of the backend still boots without GCP credentials wired up — useful
 * for local dev and CI.
 */
@ConfigurationProperties(prefix = "app.vertex")
data class ChatProperties(
    val projectId: String = "",
    val location: String = "us-central1",
    val model: String = "gemini-2.5-flash",
    val maxToolIterations: Int = 5,
    val maxHistoryTurns: Int = 10,
    val temperature: Float = 0.2f,
    val maxOutputTokens: Int = 1024,
)
