package com.todoapp.backend.chat

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ChatReportRepository : JpaRepository<ChatReportEntity, Long> {
    fun existsByUserIdAndMessageHash(userId: Long, messageHash: String): Boolean

    // Read paths added with the admin moderation queue. Before these, the repository exposed only the
    // dedup check — reports were written and never read by anything.
    fun findAllByStatusOrderByCreatedAtAsc(
        status: String,
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<ChatReportEntity>

    fun findAllByOrderByCreatedAtDesc(
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<ChatReportEntity>

    fun countByStatus(status: String): Long
}
