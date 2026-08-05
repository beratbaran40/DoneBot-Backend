package com.todoapp.backend.group

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ContentReportRepository : JpaRepository<ContentReportEntity, Long> {
    fun existsByReporterUserIdAndTargetHash(reporterUserId: Long, targetHash: String): Boolean

    fun findAllByStatusOrderByCreatedAtAsc(
        status: String,
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<ContentReportEntity>

    fun findAllByOrderByCreatedAtDesc(
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<ContentReportEntity>

    fun countByStatus(status: String): Long
}
