package com.todoapp.backend.task

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TaskDailyCompletionRepository : JpaRepository<TaskDailyCompletionEntity, Long> {
    fun findByTaskIdAndDate(taskId: Long, date: Long): TaskDailyCompletionEntity?
    fun findAllByUserIdAndDateBetween(userId: Long, fromDay: Long, toDay: Long): List<TaskDailyCompletionEntity>
    fun deleteByTaskIdAndDate(taskId: Long, date: Long)
}
