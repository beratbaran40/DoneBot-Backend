package com.todoapp.backend.pomodoro

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Batch upload. Batched rather than one-at-a-time because these rows — unlike every other synced entity
 * here — are immutable, append-only, unordered and carry their own idempotency key, which is the exact
 * shape where a batch is safe: a partial failure is retried whole and the unique index absorbs the
 * overlap. A week offline is ~50 rows, and Neon scales to zero, so fifty round trips would be fifty cold
 * starts.
 */
data class PomodoroUploadRequest(
    @field:Valid
    @field:Size(min = 1, max = 50, message = "1..50 sessions per request")
    val sessions: List<PomodoroSessionRequest>,
)

/**
 * One interval as the client reports it. Numeric fields are **clamped, not rejected** (see
 * [PomodoroService.toEntity]) — a 400 costs the whole batch of fifty, so the server only refuses input
 * it genuinely cannot interpret.
 */
data class PomodoroSessionRequest(
    @field:NotBlank @field:Size(min = 36, max = 36) val clientSessionId: String,
    @field:NotBlank @field:Size(min = 36, max = 36) val clientRunId: String,
    val sessionIndex: Int = 0,
    @field:NotBlank @field:Size(max = 16) val mode: String,
    /** Configured length of the interval. Never summed into a focus-time figure. */
    val plannedSeconds: Int,
    /** How much of it actually ran. Every focus-time figure sums this, and only this. */
    val elapsedSeconds: Int,
    /** True only when the countdown reached zero on its own. */
    val completed: Boolean,
    /** Epoch milliseconds, UTC. */
    val startedAt: Long,
    /** Epoch milliseconds, UTC. */
    val endedAt: Long,
    /** Epoch day in the device's own time zone at [endedAt]. */
    val localDate: Long,
    val tzOffsetMinutes: Int? = null,
)

/** Response shape. Identical to the upload shape so the client can round-trip one mapper. */
data class PomodoroSessionData(
    val clientSessionId: String,
    val clientRunId: String,
    val sessionIndex: Int,
    val mode: String,
    val plannedSeconds: Int,
    val elapsedSeconds: Int,
    val completed: Boolean,
    val startedAt: Long,
    val endedAt: Long,
    val localDate: Long,
    val tzOffsetMinutes: Int?,
)

data class PomodoroSessionListData(
    val items: List<PomodoroSessionData>,
    val count: Int,
)

/**
 * [duplicates] is a success, not a warning. A repeated upload of a row that already exists is a no-op by
 * design — the alternative, a 409, would poison the client's push loop permanently on the first retry.
 */
data class PomodoroUploadData(
    val accepted: Int,
    val duplicates: Int,
)
