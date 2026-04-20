package com.todoapp.backend.task

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TaskPhotoRepository : JpaRepository<TaskPhotoEntity, Long> {
    fun findAllByTaskIdOrderByCreatedAtAsc(taskId: Long): List<TaskPhotoEntity>
    fun countByTaskId(taskId: Long): Long
    fun deleteByTaskId(taskId: Long): Long
}
