package com.todoapp.backend.admin

import org.springframework.http.HttpStatus
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException

/**
 * The verified admin behind the current request, resolved once by [AdminAuthFilter] and stashed on the
 * request so controllers and the audit log can read identity without a second database round-trip.
 *
 * Note this is *not* the security principal: that stays the raw `Long` user id set by
 * [com.todoapp.backend.auth.JwtAuthFilter], so [com.todoapp.backend.common.CurrentUser.id] keeps
 * working unchanged on admin requests too.
 */
data class AdminPrincipal(
    val id: Long,
    val email: String,
    val displayName: String,
) {
    companion object {
        const val ATTRIBUTE: String = "com.todoapp.backend.admin.principal"
    }
}

object CurrentAdmin {
    /**
     * Throws 403 rather than returning null: every caller sits behind the /admin chain, so a missing
     * principal means the filter did not run — a wiring bug, not an ordinary unauthenticated request.
     */
    fun get(): AdminPrincipal {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        return attributes?.request?.getAttribute(AdminPrincipal.ATTRIBUTE) as? AdminPrincipal
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required")
    }
}
