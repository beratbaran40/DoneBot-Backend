package com.todoapp.backend.group

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Group metadata projection that skips the avatar BLOB. `hasAvatar` is computed server-side
 * (`avatar_bytes IS NOT NULL`) so the BLOB never traverses the wire.
 */
interface GroupSummary {
    val id: Long
    val name: String
    val description: String
    val ownerId: Long
    val createdAt: Instant
    val updatedAt: Instant
    val hasAvatar: Boolean
}

@Repository
interface GroupRepository : JpaRepository<GroupEntity, Long> {
    @Query(
        "SELECT g.id AS id, g.name AS name, g.description AS description, " +
            "g.ownerId AS ownerId, g.createdAt AS createdAt, g.updatedAt AS updatedAt, " +
            "(g.avatarBytes IS NOT NULL) AS hasAvatar " +
            "FROM GroupEntity g WHERE g.id = :id"
    )
    fun findSummaryById(@Param("id") id: Long): GroupSummary?

    fun findAllByOwnerId(ownerId: Long): List<GroupEntity>
}

@Repository
interface GroupMemberRepository : JpaRepository<GroupMemberEntity, Long> {
    fun findAllByGroupId(groupId: Long): List<GroupMemberEntity>
    fun findAllByUserId(userId: Long): List<GroupMemberEntity>
    fun findByGroupIdAndUserId(groupId: Long, userId: Long): GroupMemberEntity?
    fun deleteByGroupIdAndUserId(groupId: Long, userId: Long): Long
    fun countByGroupId(groupId: Long): Long
}
