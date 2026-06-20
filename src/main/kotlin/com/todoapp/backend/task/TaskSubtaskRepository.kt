package com.todoapp.backend.task

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TaskSubtaskRepository : JpaRepository<TaskSubtaskEntity, Long> {
    fun findAllByTaskIdOrderByOrderIndexAsc(taskId: Long): List<TaskSubtaskEntity>
    fun deleteByTaskId(taskId: Long)
}
