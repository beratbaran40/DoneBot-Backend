package com.todoapp.backend.user

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.common.CurrentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class UserPreferencesData(val pushEnabled: Boolean)

data class UpdateUserPreferencesRequest(val pushEnabled: Boolean)

@Service
class UserPreferencesService(
    private val repo: UserPreferencesRepository,
) {
    @Transactional
    fun get(userId: Long): UserPreferencesData {
        val row = repo.findById(userId).orElseGet {
            repo.save(UserPreferencesEntity(userId = userId))
        }
        return UserPreferencesData(pushEnabled = row.pushEnabled)
    }

    @Transactional
    fun update(userId: Long, req: UpdateUserPreferencesRequest): UserPreferencesData {
        val row = repo.findById(userId).orElseGet {
            UserPreferencesEntity(userId = userId)
        }
        row.pushEnabled = req.pushEnabled
        row.updatedAt = Instant.now()
        repo.save(row)
        return UserPreferencesData(pushEnabled = row.pushEnabled)
    }

    /** Filter the supplied user ids to those who currently have push enabled. */
    @Transactional(readOnly = true)
    fun pushEnabledUserIds(userIds: Collection<Long>): Set<Long> {
        if (userIds.isEmpty()) return emptySet()
        val explicit = repo.findAllByUserIdInAndPushEnabledTrue(userIds).map { it.userId }.toSet()
        val explicitAny = repo.findAllById(userIds).map { it.userId }.toSet()
        // Users without a row default to enabled.
        val defaulted = userIds.toSet() - explicitAny
        return explicit + defaulted
    }
}

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
