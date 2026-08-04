package com.todoapp.backend.task

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TaskDailyCompletionRepository : JpaRepository<TaskDailyCompletionEntity, Long> {
    fun findByTaskIdAndDate(taskId: Long, date: Long): TaskDailyCompletionEntity?
    fun findAllByUserIdAndDateBetween(userId: Long, fromDay: Long, toDay: Long): List<TaskDailyCompletionEntity>
    fun deleteByTaskIdAndDate(taskId: Long, date: Long)

    /**
     * Ignores `userId` on purpose: a group task's occurrence is completed for the whole group by
     * whoever ticks it first, so every member must see the row regardless of who wrote it.
     */
    fun findAllByTaskIdInAndDateBetween(
        taskIds: Collection<Long>,
        fromDay: Long,
        toDay: Long,
    ): List<TaskDailyCompletionEntity>
}
