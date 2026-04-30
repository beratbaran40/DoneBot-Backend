package com.todoapp.backend.task

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface TaskRepository : JpaRepository<TaskEntity, Long> {
    fun findAllByOwnerIdAndFamilyGroupIdIsNull(ownerId: Long): List<TaskEntity>
    fun findAllByFamilyGroupId(groupId: Long): List<TaskEntity>

    fun findFirst5ByOwnerIdAndFamilyGroupIdIsNullAndTitleContainingIgnoreCaseOrderByDateAsc(
        ownerId: Long,
        titleFragment: String,
    ): List<TaskEntity>

    @Query(
        "SELECT t FROM TaskEntity t WHERE t.familyGroupId IS NOT NULL " +
            "AND t.assignedToUserId IS NOT NULL " +
            "AND t.isCompleted = false " +
            "AND t.dueSoonNotifiedAt IS NULL " +
            "AND t.date BETWEEN :fromDay AND :toDay"
    )
    fun findDueSoonCandidates(fromDay: Long, toDay: Long): List<TaskEntity>
}
