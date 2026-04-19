package com.todoapp.backend.group

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface GroupActivityRepository : JpaRepository<GroupActivityEntity, Long> {
    fun findAllByGroupIdOrderByTimestampDesc(groupId: Long, pageable: Pageable): List<GroupActivityEntity>
}
