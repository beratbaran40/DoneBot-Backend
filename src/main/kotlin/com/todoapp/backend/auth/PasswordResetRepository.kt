package com.todoapp.backend.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface PasswordResetRepository : JpaRepository<PasswordResetEntity, Long> {
    fun findByTokenHash(tokenHash: String): PasswordResetEntity?

    @Modifying
    @Query("DELETE FROM PasswordResetEntity p WHERE p.userId = :userId")
    fun deleteAllByUserId(@Param("userId") userId: Long)
}
