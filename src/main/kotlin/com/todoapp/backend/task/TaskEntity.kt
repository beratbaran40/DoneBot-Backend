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

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "due_soon_notified_at")
    var dueSoonNotifiedAt: Instant? = null,
)
