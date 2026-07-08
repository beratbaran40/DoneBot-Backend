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
    /** Per-user request cap in any 60s window. Keeps a runaway client from spending Vertex tokens. */
    val rateLimitPerMinute: Int = 30,
    /** Per-user request cap in any 24h window. Soft daily ceiling. */
    val rateLimitPerDay: Int = 500,
    /**
     * Global (all-users) daily request ceiling — a coarse cost circuit-breaker (§4.10). Per-user
     * limits cap individuals but not total Vertex spend; over this, all chat degrades to 503.
     */
    val maxGlobalDailyRequests: Int = 5000,
    /**
     * Hard wall-clock ceiling for ONE chat turn (all Vertex rounds + tool executions). Must stay
     * comfortably below the Android client's 60s OkHttp read timeout: past that the client throws
     * SocketTimeoutException and shows a connectivity-flavored error while the server keeps
     * burning Vertex tokens on an answer nobody will receive. Hitting the deadline returns the
     * same marked 503 as a Vertex outage, which the client renders as its "AI taking a break"
     * banner (Tier 1.5).
     */
    val turnDeadlineMs: Long = 45_000,
)
