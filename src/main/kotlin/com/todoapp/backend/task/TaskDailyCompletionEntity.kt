package com.todoapp.backend.task

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "task_daily_completions",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_task_daily_completion", columnNames = ["task_id", "date"]),
    ],
    indexes = [
        Index(name = "ix_task_daily_completion_user_date", columnList = "user_id,date"),
    ],
)
class TaskDailyCompletionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "task_id", nullable = false)
    var taskId: Long,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    /** Epoch day (LocalDate.toEpochDay) */
    @Column(name = "date", nullable = false)
    var date: Long,

    @Column(name = "completed_at", nullable = false)
    var completedAt: Long,
)
