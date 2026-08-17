package com.todoapp.backend.pomodoro

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant

@Service
class PomodoroService(private val repo: PomodoroSessionRepository) {
    /**
     * Insert every session in the batch that this user does not already have.
     *
     * Returns 200 with a `duplicates` count rather than 409 on a repeat. That is a deliberate departure
     * from the task path, where a losing double-submit may carry different content and the client has to
     * re-read. A pomodoro session is a write-once, immutable fact: a duplicate is a successful no-op, and
     * answering 409 would wedge the client's push loop forever on the first retried batch.
     */
    @Transactional
    fun upload(userId: Long, req: PomodoroUploadRequest): PomodoroUploadData {
        val now = Instant.now()
        // Within-batch dedupe first: two identical client ids in one payload would otherwise both miss
        // the "already exists" probe and collide on the unique index.
        val incoming = req.sessions.distinctBy { it.clientSessionId }
        incoming.forEach { it.validate(now) }

        val existing = repo
            .findAllByUserIdAndClientSessionIdIn(userId, incoming.map { it.clientSessionId })
            .mapTo(HashSet()) { it.clientSessionId }
        val toInsert = incoming.filterNot { it.clientSessionId in existing }.map { it.toEntity(userId) }

        try {
            if (toInsert.isNotEmpty()) repo.saveAll(toInsert)
        } catch (e: DataIntegrityViolationException) {
            // Unreachable from one device: the client pushes under a mutex and generates the key locally.
            // Surfaced honestly rather than swallowed, so a genuine concurrent double-push is visible.
            throw ResponseStatusException(HttpStatus.CONFLICT, "duplicate clientSessionId", e)
        }

        return PomodoroUploadData(accepted = toInsert.size, duplicates = incoming.size - toInsert.size)
    }

    /**
     * Sign-in backfill over the client's own calendar days.
     *
     * The window is capped because the range is unindexed against abuse, not because a year is expensive:
     * the client asks for exactly 365 days once per sign-in.
     */
    @Transactional(readOnly = true)
    fun list(userId: Long, from: Long, to: Long): PomodoroSessionListData {
        if (to < from) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "to must not be before from")
        }
        if (to - from > MAX_RANGE_DAYS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "range must not exceed $MAX_RANGE_DAYS days")
        }
        val items = repo
            .findAllByUserIdAndLocalDateBetweenOrderByEndedAtAsc(userId, from, to)
            .map { it.toData() }
        return PomodoroSessionListData(items = items, count = items.size)
    }

    /**
     * Refuses only what cannot be interpreted.
     *
     * An unknown `mode` is rejected rather than coerced to FOCUS: coercing would silently inflate focus
     * time, which is the one number this whole table exists to state honestly. OVERTIME is already
     * accepted so a future client release needs no backend deploy.
     *
     * The timestamp window exists because the client supplies `ended_at` and the admin daily series is
     * chased through it — one device with a broken clock would otherwise put rows in the wrong year
     * permanently.
     */
    private fun PomodoroSessionRequest.validate(now: Instant) {
        if (mode !in ALLOWED_MODES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown mode: $mode")
        }
        val ended = Instant.ofEpochMilli(endedAt)
        if (ended.isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "endedAt is too far in the future")
        }
        if (ended.isBefore(now.minus(MAX_BACKFILL_AGE))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "endedAt is too far in the past")
        }
    }

    /**
     * Clamps rather than rejects, because a 400 costs fifty rows instead of one.
     *
     * `completed` wins over `elapsedSeconds`: a session the client says ran to completion is stored as
     * having run its full planned length, so "completed" and "elapsed == planned" can never disagree in
     * the data and no aggregate has to reconcile them.
     */
    private fun PomodoroSessionRequest.toEntity(userId: Long): PomodoroSessionEntity {
        val planned = plannedSeconds.coerceIn(1, MAX_SECONDS)
        val elapsed = if (completed) planned else elapsedSeconds.coerceIn(0, planned)
        return PomodoroSessionEntity(
            userId = userId,
            clientSessionId = clientSessionId,
            clientRunId = clientRunId,
            sessionIndex = sessionIndex.coerceAtLeast(0),
            mode = mode,
            plannedSeconds = planned,
            elapsedSeconds = elapsed,
            completed = completed,
            startedAt = Instant.ofEpochMilli(startedAt),
            endedAt = Instant.ofEpochMilli(endedAt),
            localDate = localDate,
            tzOffsetMinutes = tzOffsetMinutes,
        )
    }

    private fun PomodoroSessionEntity.toData() = PomodoroSessionData(
        clientSessionId = clientSessionId,
        clientRunId = clientRunId,
        sessionIndex = sessionIndex,
        mode = mode,
        plannedSeconds = plannedSeconds,
        elapsedSeconds = elapsedSeconds,
        completed = completed,
        startedAt = startedAt.toEpochMilli(),
        endedAt = endedAt.toEpochMilli(),
        localDate = localDate,
        tzOffsetMinutes = tzOffsetMinutes,
    )

    companion object {
        /** OVERTIME is reserved: accepted now so a v2 client needs no backend deploy. */
        private val ALLOWED_MODES = setOf("FOCUS", "SHORT_BREAK", "LONG_BREAK", "OVERTIME")
        private const val MAX_SECONDS = 86_400
        private const val MAX_RANGE_DAYS = 366L
        private val MAX_CLOCK_SKEW: Duration = Duration.ofHours(24)
        private val MAX_BACKFILL_AGE: Duration = Duration.ofDays(400)
    }
}
