package com.todoapp.backend.user

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.common.CurrentUser
import com.todoapp.backend.notif.inbox.NotificationType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class UserPreferencesData(
    val pushEnabled: Boolean,
    /** NotificationType names the user has muted. Empty = every type is delivered. */
    val disabledTypes: Set<String> = emptySet(),
)

/**
 * [disabledTypes] is nullable so an older client, which only knows the master toggle, keeps working:
 * omitting the field leaves the stored per-type choices alone instead of silently clearing them.
 */
data class UpdateUserPreferencesRequest(
    val pushEnabled: Boolean,
    val disabledTypes: Set<String>? = null,
)

@Service
class UserPreferencesService(
    private val repo: UserPreferencesRepository,
) {
    @Transactional
    fun get(userId: Long): UserPreferencesData {
        val row = repo.findById(userId).orElseGet {
            repo.save(UserPreferencesEntity(userId = userId))
        }
        return row.toData()
    }

    @Transactional
    fun update(userId: Long, req: UpdateUserPreferencesRequest): UserPreferencesData {
        val row = repo.findById(userId).orElseGet {
            UserPreferencesEntity(userId = userId)
        }
        row.pushEnabled = req.pushEnabled
        // Only rewrite the per-type set when the client actually sent one — see the request docstring.
        req.disabledTypes?.let { row.pushDisabledTypes = it.toCsv() }
        row.updatedAt = Instant.now()
        repo.save(row)
        return row.toData()
    }

    /**
     * Filter the supplied user ids to those who should receive a push of [type].
     *
     * Both gates apply: the master toggle and the per-type mute. A muted type still writes its inbox
     * row — the preference silences the interruption, it does not erase the history.
     */
    @Transactional(readOnly = true)
    fun pushEnabledUserIds(userIds: Collection<Long>, type: NotificationType): Set<Long> {
        if (userIds.isEmpty()) return emptySet()
        val rows = repo.findAllById(userIds).associateBy { it.userId }
        return userIds.toSet().filter { id ->
            // No row at all = every default, which is "push on, nothing muted".
            val row = rows[id] ?: return@filter true
            row.pushEnabled && type.name !in row.pushDisabledTypes.toTypeSet()
        }.toSet()
    }

    private fun UserPreferencesEntity.toData() = UserPreferencesData(
        pushEnabled = pushEnabled,
        disabledTypes = pushDisabledTypes.toTypeSet(),
    )
}

/** Unknown names are dropped, so a type deleted from the enum cannot resurrect as a phantom mute. */
private fun String.toTypeSet(): Set<String> = split(',')
    .map { it.trim() }
    .filter { name -> NotificationType.entries.any { it.name == name } }
    .toSet()

private fun Set<String>.toCsv(): String = filter { name -> NotificationType.entries.any { it.name == name } }
    .distinct()
    .sorted()
    .joinToString(",")

@RestController
@RequestMapping("/users/me/preferences")
class UserPreferencesController(
    private val service: UserPreferencesService,
) {
    @GetMapping
    fun get(): BaseResponse<UserPreferencesData> =
        BaseResponse.ok(service.get(CurrentUser.id()))

    @PutMapping
    fun update(@RequestBody req: UpdateUserPreferencesRequest): BaseResponse<UserPreferencesData> =
        BaseResponse.ok(service.update(CurrentUser.id(), req))
}
