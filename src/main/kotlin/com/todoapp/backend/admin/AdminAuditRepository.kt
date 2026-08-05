package com.todoapp.backend.admin

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AdminAuditRepository : JpaRepository<AdminAuditEntity, Long> {
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<AdminAuditEntity>
}
