package com.todoapp.backend.task

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

/**
 * One ordered step of a staged task. The parent is a personal [TaskEntity]; the
 * `task_subtasks.task_id` FK is `ON DELETE CASCADE` (see V11), so deleting the task
 * removes its steps. A staged task always has >=1 step (enforced by the client and the
 * chat tools). Kept as a flat sibling table (not a JPA @OneToMany on TaskEntity) to
 * match the existing TaskDailyCompletion / TaskPhoto pattern and avoid eager-load
 * surprises in the task list path.
 */
@Entity
@Table(
    name = "task_subtasks",
    indexes = [
        Index(name = "ix_task_subtasks_task", columnList = "task_id"),
    ],
)
class TaskSubtaskEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "task_id", nullable = false)
    var taskId: Long,

    @Column(nullable = false)
    var title: String,

    @Column(name = "is_completed", nullable = false)
    var isCompleted: Boolean = false,

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0,
)
