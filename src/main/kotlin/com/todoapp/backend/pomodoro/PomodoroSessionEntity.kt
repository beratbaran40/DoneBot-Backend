package com.todoapp.backend.pomodoro

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * One completed (or abandoned) pomodoro interval. Append-only: a row is written once at the moment the
 * interval ends and is never updated, so there is no lifecycle in which a field changes after insert.
 *
 * Mirrors `V30__pomodoro_sessions.sql` exactly. Under `ddl-auto=validate` a single nullability or type
 * mismatch here stops the **whole** application from starting, not just this feature — [tzOffsetMinutes]
 * is the only nullable column and must stay `Int?`.
 *
 * Deliberately a plain `class` and not a `data class`: Hibernate depends on identity semantics that a
 * `data class` breaks — all-field `equals`/`hashCode` corrupts `Set`/`Map` membership once the ID is
 * assigned, and the generated `copy()` produces detached duplicates of managed entities.
 *
 * No `equals`/`hashCode` override, matching the twelve entities already in this codebase. The usual
 * argument for ID-based equality is `Set`/`Map` membership surviving a persist; these rows are never put
 * in a hash collection, never lazily proxied and never bidirectionally associated — they are inserted
 * through `saveAll` and read back by field. Adding the override here would diverge from every sibling
 * entity to defend against a shape this table does not have.
 *
 * [userId] is a plain `Long`, not a `@ManyToOne` to the user. Nothing on this row needs to navigate to
 * the user, and the FK plus `ON DELETE CASCADE` lives in the migration where account deletion relies on
 * it — the same choice `TaskDailyCompletionEntity` makes.
 */
@Entity
@Table(
    name = "pomodoro_sessions",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_pomodoro_session_client", columnNames = ["user_id", "client_session_id"]),
    ],
    indexes = [
        Index(name = "idx_pomodoro_sessions_user_local_date", columnList = "userId,localDate"),
        Index(name = "idx_pomodoro_sessions_ended_at", columnList = "endedAt"),
    ],
)
class PomodoroSessionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    /** Client-generated UUIDv4. The idempotency key: a retried batch dedupes on it instead of doubling focus time. */
    @Column(name = "client_session_id", nullable = false, length = 36)
    var clientSessionId: String,

    /** Client-generated UUIDv4 shared by every interval of one sitting. */
    @Column(name = "client_run_id", nullable = false, length = 36)
    var clientRunId: String,

    /** Zero-based position within the run. Preserves order without trusting timestamps. */
    @Column(name = "session_index", nullable = false)
    var sessionIndex: Int = 0,

    /** FOCUS | SHORT_BREAK | LONG_BREAK, plus a reserved OVERTIME. Unconstrained on purpose — see the V30 header. */
    @Column(name = "mode", nullable = false, length = 16)
    var mode: String,

    /** The configured length of this interval. Never summed into a focus-time figure. */
    @Column(name = "planned_seconds", nullable = false)
    var plannedSeconds: Int,

    /** How much of the interval actually ran. Every focus-time figure sums this, and only this. */
    @Column(name = "elapsed_seconds", nullable = false)
    var elapsedSeconds: Int,

    /** True only when the countdown reached zero on its own. */
    @Column(name = "completed", nullable = false)
    var completed: Boolean,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant,

    /** The instant the interval ended. Every admin aggregate is chased through this, in UTC. */
    @Column(name = "ended_at", nullable = false)
    var endedAt: Instant,

    /** Epoch day in the *device's* time zone at [endedAt] — see the V30 header for why both clocks exist. */
    @Column(name = "local_date", nullable = false)
    var localDate: Long,

    /** Device UTC offset in minutes at [endedAt]. The only nullable column; keep it `Int?`. */
    @Column(name = "tz_offset_minutes")
    var tzOffsetMinutes: Int? = null,
)
