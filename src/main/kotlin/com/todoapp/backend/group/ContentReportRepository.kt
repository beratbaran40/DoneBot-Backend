package com.todoapp.backend.group

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ContentReportRepository : JpaRepository<ContentReportEntity, Long> {
    fun existsByReporterUserIdAndTargetHash(reporterUserId: Long, targetHash: String): Boolean
}
