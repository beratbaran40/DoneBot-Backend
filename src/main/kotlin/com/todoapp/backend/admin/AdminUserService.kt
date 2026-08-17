package com.todoapp.backend.admin

import com.todoapp.backend.auth.RefreshTokenRepository
import com.todoapp.backend.notif.DeviceTokenRepository
import com.todoapp.backend.notif.NotificationPublisher
import com.todoapp.backend.notif.inbox.NotificationType
import com.todoapp.backend.user.AccountDeletionService
import com.todoapp.backend.user.UserRepository
import com.todoapp.backend.user.UserRole
import com.todoapp.backend.user.UserStatus
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ---- payloads ------------------------------------------------------------------------------------

data class AdminUserListItem(
    val id: Long,
    val email: String,
    val displayName: String,
    val role: String,
    val status: String,
    val providers: List<String>,
    val emailVerified: Boolean,
    val createdAt: String,
    val lastActiveAt: String?,
    val taskCount: Long,
    val groupCount: Long,
)

data class AdminUserPage(
    val items: List<AdminUserListItem>,
    val page: Int,
    val size: Int,
    val total: Long,
)

data class AdminUserCounts(
    val tasksTotal: Long,
    val tasksPersonal: Long,
    val tasksGroup: Long,
    val tasksCompleted: Long,
    val tasksSecret: Long,
    val routineCompletions: Long,
    val photos: Long,
    val notificationsUnread: Long,
)

data class AdminUserGroup(
    val groupId: Long,
    val name: String,
    val role: String,
    val isOwner: Boolean,
    val memberCount: Long,
    val joinedAt: String?,
)

data class AdminUserDevice(
    val id: Long,
    val deviceName: String?,
    val deviceId: String?,
    val tokenSuffix: String,
    val createdAt: String?,
    val updatedAt: String?,
)

data class AdminUserChatUsage(
    val requests: Long,
    val refusals: Long,
    val errors: Long,
    val promptTokens: Long,
    val responseTokens: Long,
)

/**
 * Focus totals only. [sessionsStarted] counts every FOCUS interval that left a row and
 * [sessionsCompleted] only those that ran to zero — a session killed by process death leaves no row, so
 * the ratio reads optimistically.
 */
data class AdminUserPomodoro(
    val focusMinutes: Long,
    val sessionsCompleted: Long,
    val sessionsStarted: Long,
    val runs: Long,
)

data class AdminUserSessions(val activeRefreshTokens: Int, val lastRefreshAt: String?)

/**
 * Everything the support screen shows about one account — and nothing more.
 *
 * The omissions are the design. No task titles, no task descriptions, no journal, no chat transcript,
 * no other user's email, no password hash, no avatar bytes, no full device token, and **no individual
 * focus-session times** — pomodoro is aggregated into totals and never listed, because a list of session
 * timestamps is a minute-by-minute record of when this person was at their desk. Support questions
 * ("did my task sync?", "why did I stop getting reminders?") are answerable from counts, dates and
 * device registrations; reading someone's actual to-do list is not required to answer them, and the
 * privacy policy this product ships under does not promise otherwise.
 */
data class AdminUserDetail(
    val id: Long,
    val email: String,
    val displayName: String,
    val role: String,
    val status: String,
    val suspendedAt: String?,
    val suspendedReason: String?,
    val emailVerified: Boolean,
    val providers: List<String>,
    val avatarUrl: String?,
    val createdAt: String,
    val lastActiveAt: String?,
    val activeDaysLast30: Int,
    /** 30 booleans as 0/1, oldest first — a sparkline the panel can draw without another request. */
    val activitySparkline: List<Int>,
    val counts: AdminUserCounts,
    val groups: List<AdminUserGroup>,
    val devices: List<AdminUserDevice>,
    val chatUsage30d: AdminUserChatUsage,
    /** Aggregated focus totals. Individual session times are never listed — see the class KDoc. */
    val pomodoro30d: AdminUserPomodoro,
    val reportsFiled: Int,
    val reportsAgainst: Int,
    val sessions: AdminUserSessions,
)

data class SuspendUserRequest(val reason: String? = null)

data class DeleteUserRequest(val confirmEmail: String)

// ---- service -------------------------------------------------------------------------------------

@Service
class AdminUserService(
    private val users: UserRepository,
    private val query: AdminUserQueryRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val deviceTokens: DeviceTokenRepository,
    private val accountDeletion: AccountDeletionService,
    private val notificationPublisher: NotificationPublisher,
    private val audit: AdminAuditService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun search(filter: AdminUserFilter, sort: AdminUserSort, page: Int, size: Int) = AdminUserPage(
        items = query.search(filter, sort, page, size),
        page = page,
        size = size,
        total = query.count(filter),
    )

    @Transactional(readOnly = true)
    fun detail(userId: Long): AdminUserDetail {
        val user = users.findById(userId).orElseThrow { notFound() }
        val today = LocalDate.now(ZoneOffset.UTC)
        val windowStart = today.minusDays(SPARKLINE_DAYS - 1L)
        val activeDays = query.activeDays(userId, windowStart)
        val (filed, against) = query.reportCounts(userId)

        return AdminUserDetail(
            id = user.id,
            email = user.email,
            displayName = user.displayName,
            role = user.role,
            status = user.status,
            suspendedAt = user.suspendedAt?.toString(),
            suspendedReason = user.suspendedReason,
            emailVerified = user.emailVerified,
            providers = user.providers,
            avatarUrl = user.avatarUrl,
            createdAt = user.createdAt.toString(),
            lastActiveAt = user.lastActiveAt?.toString(),
            activeDaysLast30 = activeDays.size,
            activitySparkline = (0 until SPARKLINE_DAYS).map { offset ->
                if (windowStart.plusDays(offset.toLong()) in activeDays) 1 else 0
            },
            counts = query.counts(userId),
            groups = query.groups(userId),
            devices = query.devices(userId),
            chatUsage30d = query.chatUsage(userId, today.minusDays(29)),
            pomodoro30d = query.pomodoro(
                userId,
                today.minusDays(29).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime(),
            ),
            reportsFiled = filed,
            reportsAgainst = against,
            sessions = query.sessions(userId),
        )
    }

    /**
     * Suspending does three things, and all three are needed for it to mean anything:
     * flips the status so login and refresh refuse the account, revokes every refresh token so no new
     * access token can be minted, and drops the device registrations so pushes stop immediately.
     *
     * What it cannot do is invalidate an access token already in someone's hand — that expires on its
     * own within the hour. See AuthService.requireNotSuspended for why that trade was chosen.
     */
    @Transactional
    fun suspend(userId: Long, reason: String?) {
        val user = users.findById(userId).orElseThrow { notFound() }
        requireNotSelf(userId, "suspend")
        requireNotAdmin(user.role, "suspend")

        user.status = UserStatus.SUSPENDED.name
        user.suspendedAt = Instant.now()
        user.suspendedReason = reason?.take(REASON_MAX_LENGTH)
        users.save(user)

        refreshTokens.revokeAllByUserId(userId)
        deviceTokens.deleteAll(deviceTokens.findAllByUserId(userId))

        audit.record(
            action = AdminAction.USER_SUSPEND,
            targetType = "user",
            targetId = userId.toString(),
            detail = "reason=${reason ?: ""}",
        )
    }

    @Transactional
    fun unsuspend(userId: Long) {
        val user = users.findById(userId).orElseThrow { notFound() }
        user.status = UserStatus.ACTIVE.name
        user.suspendedAt = null
        user.suspendedReason = null
        users.save(user)
        audit.record(AdminAction.USER_UNSUSPEND, targetType = "user", targetId = userId.toString())
    }

    /** Signs the account out everywhere without suspending it — the gentler support action. */
    @Transactional
    fun revokeSessions(userId: Long) {
        users.findById(userId).orElseThrow { notFound() }
        refreshTokens.revokeAllByUserId(userId)
        audit.record(AdminAction.USER_REVOKE_SESSIONS, targetType = "user", targetId = userId.toString())
    }

    /**
     * Deletes an account through the same path the user's own "delete my account" uses, so group
     * ownership transfer, cascades and GDPR semantics stay identical rather than being reimplemented.
     *
     * Two guards make this hard to do by accident: the caller must echo the account's exact email, and
     * neither the acting admin nor another admin can be deleted through this endpoint.
     */
    fun delete(userId: Long, confirmEmail: String) {
        val user = users.findById(userId).orElseThrow { notFound() }
        requireNotSelf(userId, "delete")
        requireNotAdmin(user.role, "delete")
        if (!user.email.equals(confirmEmail.trim(), ignoreCase = true)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "confirmEmail does not match the account being deleted",
            )
        }
        val email = user.email

        val result = accountDeletion.deleteAccount(userId)
        auditDeletion(userId, email)

        // The new-owner fan-out lives in UserController.deleteMe rather than in the deletion service, so
        // it has to be repeated here — otherwise an admin deletion would silently hand someone a group
        // without telling them. Best-effort: a push failure must not undo the deletion.
        result.transferredGroups.forEach { transferred ->
            runCatching {
                notificationPublisher.publish(
                    userIds = listOf(transferred.newOwnerId),
                    type = NotificationType.GROUP_OWNERSHIP_TRANSFERRED,
                    title = "You're now the admin of ${transferred.groupName}",
                    body = "Tap to open the group",
                    payload = mapOf(
                        "groupId" to transferred.groupId.toString(),
                        "groupName" to transferred.groupName,
                    ),
                )
            }.onFailure { log.warn("Ownership-transfer notification failed for {}", transferred.newOwnerId, it) }
        }
    }

    @Transactional
    fun auditDeletion(userId: Long, email: String) {
        audit.record(
            action = AdminAction.USER_DELETE,
            targetType = "user",
            targetId = userId.toString(),
            detail = "email=$email",
        )
    }

    private fun requireNotSelf(userId: Long, action: String) {
        if (userId == CurrentAdmin.get().id) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "An admin cannot $action their own account")
        }
    }

    private fun requireNotAdmin(role: String, action: String) {
        // Admins are removed by changing the database column, deliberately not through this API. It
        // keeps the panel from becoming a way for one compromised admin session to lock out the others.
        if (role == UserRole.ADMIN.name) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Admin accounts cannot be ${action}ed here")
        }
    }

    private fun notFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

    private companion object {
        const val SPARKLINE_DAYS = 30
        const val REASON_MAX_LENGTH = 500
    }
}
