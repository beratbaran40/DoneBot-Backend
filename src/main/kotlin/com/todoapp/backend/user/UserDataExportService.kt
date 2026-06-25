package com.todoapp.backend.user

import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.group.GroupRepository
import com.todoapp.backend.task.TaskEntity
import com.todoapp.backend.task.TaskRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * GDPR Article 15/20 "right of access / data portability": gathers everything DoneBot holds for a
 * user on the server into one JSON payload they can download from Settings. Read-only; reuses the
 * same per-user queries as [AccountDeletionService] so the export and the delete cover the same set.
 *
 * Deliberately excluded: password hash, avatar bytes, refresh/reset tokens (secrets), and other
 * users' data. Chat history + journal entries are client-local (never sent to the server), so they
 * are not — and cannot be — included here; the [UserDataExport.note] says so.
 */
@Service
class UserDataExportService(
    private val users: UserRepository,
    private val tasks: TaskRepository,
    private val members: GroupMemberRepository,
    private val groups: GroupRepository,
    private val preferences: UserPreferencesRepository,
) {
    @Transactional(readOnly = true)
    fun export(userId: Long): UserDataExport {
        val user = users.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        val prefs = preferences.findByUserId(userId)
        val personalTasks = tasks.findAllByOwnerIdAndFamilyGroupIdIsNull(userId).map { it.toExport() }
        val memberships = members.findAllByUserId(userId).map { m ->
            val name = groups.findById(m.groupId).map { it.name }.orElse("(deleted group)")
            ExportMembership(groupId = m.groupId, groupName = name, role = m.role, joinedAt = m.joinedAt.toString())
        }
        return UserDataExport(
            exportedAt = Instant.now().toString(),
            profile = ExportProfile(
                email = user.email,
                displayName = user.displayName,
                emailVerified = user.emailVerified,
                signInProviders = user.providers,
                createdAt = user.createdAt.toString(),
            ),
            preferences = prefs?.let { ExportPreferences(pushEnabled = it.pushEnabled, updatedAt = it.updatedAt.toString()) },
            personalTasks = personalTasks,
            groupMemberships = memberships,
            note = "Personal data DoneBot holds on its servers for your account. Chat history and " +
                "journal entries live only on your device and are not included here.",
        )
    }
}

data class UserDataExport(
    val exportedAt: String,
    val profile: ExportProfile,
    val preferences: ExportPreferences?,
    val personalTasks: List<ExportTask>,
    val groupMemberships: List<ExportMembership>,
    val note: String,
)

data class ExportProfile(
    val email: String,
    val displayName: String,
    val emailVerified: Boolean,
    val signInProviders: List<String>,
    val createdAt: String,
)

data class ExportPreferences(
    val pushEnabled: Boolean,
    val updatedAt: String,
)

data class ExportTask(
    val title: String,
    val description: String?,
    val date: Long,
    val timeStart: Long,
    val timeEnd: Long,
    val isAllDay: Boolean,
    val isCompleted: Boolean,
    val isSecret: Boolean,
    val priority: String?,
    val category: String,
    val customCategoryName: String?,
    val recurrence: String,
    val reminderOffsetMinutes: Long,
    val locationName: String?,
    val locationAddress: String?,
    val createdAt: String,
    val finishedOn: Long?,
)

data class ExportMembership(
    val groupId: Long,
    val groupName: String,
    val role: String,
    val joinedAt: String,
)

private fun TaskEntity.toExport() = ExportTask(
    title = title,
    description = description,
    date = date,
    timeStart = timeStart,
    timeEnd = timeEnd,
    isAllDay = isAllDay,
    isCompleted = isCompleted,
    isSecret = isSecret,
    priority = priority,
    category = category.name,
    customCategoryName = customCategoryName,
    recurrence = recurrence.name,
    reminderOffsetMinutes = reminderOffsetMinutes,
    locationName = locationName,
    locationAddress = locationAddress,
    createdAt = createdAt.toString(),
    finishedOn = finishedOn,
)
