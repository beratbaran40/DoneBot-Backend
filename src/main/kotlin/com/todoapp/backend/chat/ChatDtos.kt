package com.todoapp.backend.chat

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/** Inbound payload for `POST /chat/message`. */
data class ChatMessageRequest(
    @field:NotBlank
    @field:Size(max = MAX_PROMPT_LENGTH)
    val prompt: String,

    /** ISO 639-1 language code (`en` / `tr`). Falls back to `en` when unknown. */
    @field:Pattern(regexp = "^[a-z]{2}$", message = "locale must be a 2-letter ISO 639-1 code")
    val locale: String? = null,

    /** Last N turns the client wants the model to see. The server will trim further. */
    val history: List<ChatHistoryTurn> = emptyList(),
) {
    companion object {
        const val MAX_PROMPT_LENGTH = 1000
    }
}

/** A single previous turn in the conversation. The client owns this list. */
data class ChatHistoryTurn(
    /** "user" or "assistant" — anything else is ignored. */
    val role: String,
    val content: String,
)

/** Outbound payload from the chat endpoint. */
data class ChatMessageResponse(
    /** Final text the user should see in the assistant bubble. */
    val text: String,

    /** Per-turn metrics for client-side telemetry parity with DoneBotMetrics. */
    val meta: ChatTurnMeta,
)

data class ChatTurnMeta(
    /** How many round-trips with Vertex AI the server made. */
    val roundTrips: Int,
    /** Whether the reply matched a known refusal template. */
    val refused: Boolean,
    /** Wall-clock duration on the server, ms. */
    val serverMs: Long,
)
