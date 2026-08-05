package com.todoapp.backend.admin

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.user.UserRole
import com.todoapp.backend.user.UserStatus
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

@RestController
@RequestMapping("/admin/users")
class AdminUserController(private val service: AdminUserService) {

    /**
     * The one endpoint on this surface that can return many users' personal data at once, so both of
     * its dangerous knobs are pinned:
     *
     *  - `size` is hard-capped server-side. Without it, `?size=100000` is a single-request dump of every
     *    email address in the product.
     *  - `sort` is an enum, never a raw property name reaching the query builder.
     */
    @GetMapping
    fun search(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) role: String?,
        @RequestParam(required = false) provider: String?,
        @RequestParam(required = false) activeSince: String?,
        @RequestParam(defaultValue = "CREATED_DESC") sort: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
    ): BaseResponse<AdminUserPage> {
        val filter = AdminUserFilter(
            query = q,
            status = status?.uppercase()?.also { it.requireOneOf(UserStatus.entries.map(UserStatus::name), "status") },
            role = role?.uppercase()?.also { it.requireOneOf(UserRole.entries.map(UserRole::name), "role") },
            provider = provider?.lowercase()?.takeIf { it.matches(PROVIDER_PATTERN) },
            activeSince = activeSince?.takeIf { it.isNotBlank() }?.let { parseDate(it) },
        )
        val order = runCatching { AdminUserSort.valueOf(sort.uppercase()) }.getOrNull()
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "sort must be one of ${AdminUserSort.entries}",
            )
        return BaseResponse.ok(
            service.search(filter, order, page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE)),
        )
    }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long): BaseResponse<AdminUserDetail> = BaseResponse.ok(service.detail(id))

    @PostMapping("/{id}/suspend")
    fun suspend(@PathVariable id: Long, @RequestBody(required = false) body: SuspendUserRequest?): BaseResponse<Unit> {
        service.suspend(id, body?.reason)
        return BaseResponse.ok()
    }

    @PostMapping("/{id}/unsuspend")
    fun unsuspend(@PathVariable id: Long): BaseResponse<Unit> {
        service.unsuspend(id)
        return BaseResponse.ok()
    }

    @PostMapping("/{id}/revoke-sessions")
    fun revokeSessions(@PathVariable id: Long): BaseResponse<Unit> {
        service.revokeSessions(id)
        return BaseResponse.ok()
    }

    /**
     * Requires the account's exact email in the body. A path parameter alone is one mistyped digit away
     * from deleting the wrong person's data irreversibly, and there is no undo behind this.
     */
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long, @RequestBody body: DeleteUserRequest): BaseResponse<Unit> {
        service.delete(id, body.confirmEmail)
        return BaseResponse.ok()
    }

    private fun parseDate(value: String) = try {
        LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()
    } catch (_: DateTimeParseException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "activeSince must be ISO yyyy-MM-dd")
    }

    private fun String.requireOneOf(allowed: List<String>, field: String) {
        if (this !in allowed) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field must be one of $allowed")
        }
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
        val PROVIDER_PATTERN = Regex("^[a-z]{1,20}$")
    }
}
