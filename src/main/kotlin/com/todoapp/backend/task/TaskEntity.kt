package com.todoapp.backend.task

import jakarta.persistence.Column
import jakarta.persistence.Entity
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

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "due_soon_notified_at")
    var dueSoonNotifiedAt: Instant? = null,
)
