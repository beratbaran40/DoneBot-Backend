package com.todoapp.backend.admin

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One row per administrative write. Append-only: nothing in the codebase updates or deletes these.
 *
 * [actorUserId] has no foreign key and [actorEmail] is denormalised on purpose — the record must
 * survive deletion of the admin account that created it (see V21).
 */
@Entity
@Table(name = "admin_audit_log")
class AdminAuditEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    var actorUserId: Long,

    @Column(name = "actor_email", nullable = false, updatable = false)
    var actorEmail: String,

    @Column(nullable = false, updatable = false, length = 48)
    var action: String,

    @Column(name = "target_type", nullable = true, updatable = false, length = 24)
    var targetType: String? = null,

    @Column(name = "target_id", nullable = true, updatable = false, length = 64)
    var targetId: String? = null,

    @Column(nullable = true, updatable = false, length = 1000)
    var detail: String? = null,

    @Column(name = "request_id", nullable = true, updatable = false, length = 64)
    var requestId: String? = null,

    @Column(nullable = true, updatable = false, length = 64)
    var ip: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

/** Stable action names. Kept as constants so the audit list can be filtered on exact values. */
object AdminAction {
    const val USER_SUSPEND = "user.suspend"
    const val USER_UNSUSPEND = "user.unsuspend"
    const val USER_DELETE = "user.delete"
    const val USER_REVOKE_SESSIONS = "user.revoke_sessions"
    const val USER_PUSH = "user.push"
    const val REPORT_RESOLVE = "report.resolve"
    const val REPORT_VIEW_PHOTO = "report.view_photo"
    const val SETTING_UPDATE = "setting.update"
}
