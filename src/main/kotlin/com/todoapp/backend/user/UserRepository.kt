package com.todoapp.backend.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * Lightweight projection that selects only metadata, never the avatar BLOB. The `hasAvatar`
 * flag is computed in SQL (`avatar_bytes IS NOT NULL`) so the byte payload never crosses
 * the wire from Postgres to the backend. Used everywhere callers need name/email/avatarUrl
 * but don't need the bytes themselves.
 */
interface UserSummary {
    val id: Long
    val displayName: String
    val email: String
    val avatarUrl: String?
    val hasAvatar: Boolean
    val emailVerified: Boolean
    val providersCsv: String
    val createdAt: java.time.Instant

    /** Carried so refresh can refuse a suspended account without a second query. */
    val status: String
}

/**
 * Projection for auth flows that only need the password hash + provider list. Carries `status` so
 * login/refresh can refuse a SUSPENDED account without a second query, and `role` so the same read
 * serves both purposes.
 */
interface UserCredentials {
    val id: Long
    val email: String
    val displayName: String
    val passwordHash: String?
    val providersCsv: String
    val role: String
    val status: String
}

/**
 * The three facts the admin gate needs, read fresh on every /admin request. Deliberately not
 * carried in the JWT: a role baked into a token stays valid for the full access-token TTL with no
 * revocation path, which is unacceptable on a surface that can delete accounts.
 */
interface AdminIdentity {
    val id: Long
    val email: String
    val displayName: String
    val role: String
    val status: String
}

@Repository
interface UserRepository : JpaRepository<UserEntity, Long> {
    fun findByEmail(email: String): UserEntity?
    fun existsByEmail(email: String): Boolean

    @Query(
        "SELECT u.id AS id, u.displayName AS displayName, u.email AS email, " +
            "u.avatarUrl AS avatarUrl, " +
            "(u.avatarBytes IS NOT NULL) AS hasAvatar, " +
            "u.emailVerified AS emailVerified, u.providersCsv AS providersCsv, " +
            "u.createdAt AS createdAt, u.status AS status " +
            "FROM UserEntity u WHERE u.id = :id"
    )
    fun findSummaryById(@Param("id") id: Long): UserSummary?

    @Query(
        "SELECT u.id AS id, u.displayName AS displayName, u.email AS email, " +
            "u.avatarUrl AS avatarUrl, " +
            "(u.avatarBytes IS NOT NULL) AS hasAvatar, " +
            "u.emailVerified AS emailVerified, u.providersCsv AS providersCsv, " +
            "u.createdAt AS createdAt, u.status AS status " +
            "FROM UserEntity u WHERE u.email = :email"
    )
    fun findSummaryByEmail(@Param("email") email: String): UserSummary?

    @Query(
        "SELECT u.id AS id, u.displayName AS displayName, u.email AS email, " +
            "u.avatarUrl AS avatarUrl, " +
            "(u.avatarBytes IS NOT NULL) AS hasAvatar, " +
            "u.emailVerified AS emailVerified, u.providersCsv AS providersCsv, " +
            "u.createdAt AS createdAt, u.status AS status " +
            "FROM UserEntity u WHERE u.id IN :ids"
    )
    fun findAllSummariesByIdIn(@Param("ids") ids: Collection<Long>): List<UserSummary>

    @Query(
        "SELECT u.id AS id, u.email AS email, u.displayName AS displayName, " +
            "u.passwordHash AS passwordHash, u.providersCsv AS providersCsv, " +
            "u.role AS role, u.status AS status " +
            "FROM UserEntity u WHERE u.email = :email"
    )
    fun findCredentialsByEmail(@Param("email") email: String): UserCredentials?

    @Query(
        "SELECT u.id AS id, u.email AS email, u.displayName AS displayName, " +
            "u.passwordHash AS passwordHash, u.providersCsv AS providersCsv, " +
            "u.role AS role, u.status AS status " +
            "FROM UserEntity u WHERE u.id = :id"
    )
    fun findCredentialsById(@Param("id") id: Long): UserCredentials?

    @Query(
        "SELECT u.id AS id, u.email AS email, u.displayName AS displayName, " +
            "u.role AS role, u.status AS status " +
            "FROM UserEntity u WHERE u.id = :id"
    )
    fun findAdminIdentityById(@Param("id") id: Long): AdminIdentity?
}
