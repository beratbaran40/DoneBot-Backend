package com.todoapp.backend.task

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TaskRepository : JpaRepository<TaskEntity, Long> {
    fun findAllByOwnerIdAndFamilyGroupIdIsNull(ownerId: Long): List<TaskEntity>
    fun findAllByFamilyGroupId(groupId: Long): List<TaskEntity>
}
