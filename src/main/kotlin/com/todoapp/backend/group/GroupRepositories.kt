package com.todoapp.backend.group

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface GroupRepository : JpaRepository<GroupEntity, Long>

@Repository
interface GroupMemberRepository : JpaRepository<GroupMemberEntity, Long> {
    fun findAllByGroupId(groupId: Long): List<GroupMemberEntity>
    fun findAllByUserId(userId: Long): List<GroupMemberEntity>
    fun findByGroupIdAndUserId(groupId: Long, userId: Long): GroupMemberEntity?
    fun deleteByGroupIdAndUserId(groupId: Long, userId: Long): Long
    fun countByGroupId(groupId: Long): Long
}
