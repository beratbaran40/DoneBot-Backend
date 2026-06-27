package com.todoapp.backend.chat

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ChatReportRepository : JpaRepository<ChatReportEntity, Long> {
    fun existsByUserIdAndMessageHash(userId: Long, messageHash: String): Boolean
}
