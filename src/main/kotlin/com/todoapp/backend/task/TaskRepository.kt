package com.todoapp.backend.task

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface TaskRepository : JpaRepository<TaskEntity, Long> {
    fun findAllByOwnerIdAndFamilyGroupIdIsNull(ownerId: Long): List<TaskEntity>
    fun findAllByFamilyGroupId(groupId: Long): List<TaskEntity>

    /** Every group task across all the groups a user belongs to, in one query rather than N. */
    fun findAllByFamilyGroupIdIn(groupIds: Collection<Long>): List<TaskEntity>

    /**
     * The two numbers the chat `[Context]` block needs about shared tasks. It used to get them by
     * hydrating every task of every group the user belongs to — on EVERY chat turn — and counting in
     * memory, which is a lot of entities to throw away for two integers.
     *
     * `isSecret` is excluded to match `ChatToolService.runGetGroupTasks`: the summary must not count
     * tasks the model is then forbidden to list.
     */
    @Query(
        "SELECT COUNT(t) FROM TaskEntity t WHERE t.familyGroupId IN :groupIds " +
            "AND t.isCompleted = false AND t.isSecret = false"
    )
    fun countOpenGroupTasks(groupIds: Collection<Long>): Long

    @Query(
        "SELECT COUNT(t) FROM TaskEntity t WHERE t.familyGroupId IN :groupIds " +
            "AND t.isCompleted = false AND t.isSecret = false AND t.assignedToUserId = :userId"
    )
    fun countOpenGroupTasksAssignedTo(groupIds: Collection<Long>, userId: Long): Long

    /** §4.12 idempotency: return the existing task for a client-generated key so a retried create dedups. */
    fun findByOwnerIdAndClientTaskId(ownerId: Long, clientTaskId: String): TaskEntity?

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
