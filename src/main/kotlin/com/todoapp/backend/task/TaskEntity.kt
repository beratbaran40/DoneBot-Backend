package com.todoapp.backend.task

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "tasks",
    indexes = [
        Index(name = "idx_tasks_owner", columnList = "ownerId"),
        Index(name = "idx_tasks_group", columnList = "familyGroupId"),
    ],
)
class TaskEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false)
    var ownerId: Long,

    /**
     * Client-generated idempotency key (UUID) so a retried create dedups instead of inserting a
     * duplicate; null for legacy rows and old clients. Unique per owner via idx_tasks_owner_client. §4.12
     */
    @Column(name = "client_task_id", length = 36)
    var clientTaskId: String? = null,

    @Column(nullable = false)
    var title: String,

    @Column(length = 2000)
    var description: String? = null,

    /** Epoch day (LocalDate.toEpochDay) */
    @Column(nullable = false)
    var date: Long,

    /** Seconds since midnight */
    @Column(nullable = false)
    var timeStart: Long,

    @Column(nullable = false)
    var timeEnd: Long,

    @Column(nullable = false)
    var isCompleted: Boolean = false,

    @Column(nullable = false)
    var isSecret: Boolean = false,

    @Column
    var familyGroupId: Long? = null,

    @Column
    var assignedToUserId: Long? = null,

    @Column
    var priority: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    var category: TaskCategory = TaskCategory.PERSONAL,

    @Column(name = "custom_category_name", length = 64)
    var customCategoryName: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence", nullable = false, length = 16)
    var recurrence: Recurrence = Recurrence.NONE,

    @Column(name = "is_all_day", nullable = false)
    var isAllDay: Boolean = false,

    @Column(name = "reminder_offset_minutes", nullable = false)
    var reminderOffsetMinutes: Long = 0L,

    @Column(name = "location_lat", precision = 9, scale = 6)
    var locationLat: java.math.BigDecimal? = null,

    @Column(name = "location_lng", precision = 9, scale = 6)
    var locationLng: java.math.BigDecimal? = null,

    @Column(name = "location_name", length = 120)
    var locationName: String? = null,

    @Column(name = "location_address", length = 500)
    var locationAddress: String? = null,

    /** Epoch day a recurring routine was finished/retired from the client (null = active). */
    @Column(name = "finished_on")
    var finishedOn: Long? = null,

    /**
     * When this task was marked done, for one-off tasks.
     *
     * Distinct from both neighbours it sits between: [finishedOn] retires a recurring rule, and
     * task_daily_completions records per-day ticks of a routine. Neither says when an ordinary task got
     * finished, which left "how many tasks were completed yesterday" — the headline number for a to-do
     * app — unanswerable. Null on rows completed before V27; that history was never timestamped and
     * inventing it from created_at would be fiction.
     */
    @Column(name = "completed_at", nullable = true)
    var completedAt: java.time.Instant? = null,

    /** RRULE INTERVAL: fire every N periods of [recurrence]. 1 = every period, i.e. the legacy rule. */
    @Column(name = "recurrence_interval", nullable = false)
    var recurrenceInterval: Int = 1,

    /**
     * RRULE BYDAY: CSV of java.time.DayOfWeek names ("MONDAY,WEDNESDAY,FRIDAY"). Null = derive the
     * weekday from [date], which is the legacy WEEKLY behaviour. Only meaningful for WEEKLY.
     */
    @Column(name = "recurrence_by_day", length = 64)
    var recurrenceByDay: String? = null,

    /**
     * RRULE UNTIL: last epoch day the rule may fire, inclusive. Null = open-ended.
     *
     * Deliberately distinct from [finishedOn]: this is the end SCHEDULED at creation ("take this for
     * a month"), finishedOn is the manual retire. Both cut the rule off, the earlier one wins. See V19.
     */
    @Column(name = "recurrence_until")
    var recurrenceUntil: Long? = null,

    /**
     * Absolute reminder times of day, CSV of SECOND-of-day ("28800,50400,72000"). Null/blank = the
     * single [reminderOffsetMinutes] reminder. Stored and synced only — never scheduled here, because
     * personal-task reminders are client-local exact alarms (see TaskDueSoonJob).
     */
    @Column(name = "reminder_times", length = 128)
    var reminderTimes: String? = null,

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "due_soon_notified_at")
    var dueSoonNotifiedAt: Instant? = null,
)
