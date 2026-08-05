package com.todoapp.backend.admin

import com.todoapp.backend.user.UserRepository
import com.todoapp.backend.user.UserRole
import com.todoapp.backend.user.UserStatus
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Upgrades an ordinary authenticated request to an admin one, for the /admin chain only.
 *
 * Runs after [com.todoapp.backend.auth.JwtAuthFilter] has put the `Long` user id in the security
 * context, and before authorization is evaluated. It grants ROLE_ADMIN only when all three gates pass:
 * role is ADMIN, status is ACTIVE, and the email is on the configured allowlist. Anything else simply
 * leaves the authentication untouched, so `hasRole("ADMIN")` denies and Spring Security produces the
 * 401/403 — this filter never writes a response itself.
 *
 * The role is read **fresh from the database on every request** rather than carried as a JWT claim.
 * That costs one indexed primary-key read on a surface that sees a handful of requests, and buys
 * instant promote/demote/suspend instead of a stale-token window as long as the access-token TTL.
 *
 * ⚠️ This class is intentionally NOT a @Component. Spring Boot auto-registers every `Filter` bean into
 * the servlet filter chain, which would run it on every request the Android app makes. It is
 * instantiated by hand in [AdminSecurityConfig] so it exists only inside the admin chain.
 */
class AdminAuthFilter(
    private val userRepository: UserRepository,
    private val properties: AdminProperties,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val existing = SecurityContextHolder.getContext().authentication
        val userId = existing?.principal as? Long
        if (userId != null) {
            val identity = userRepository.findAdminIdentityById(userId)
            if (identity != null &&
                identity.role == UserRole.ADMIN.name &&
                identity.status == UserStatus.ACTIVE.name &&
                properties.isAllowed(identity.email)
            ) {
                // Principal stays the Long user id so CurrentUser.id() behaves identically here.
                val upgraded = UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    listOf(SimpleGrantedAuthority(ROLE_ADMIN)),
                )
                upgraded.details = existing.details
                SecurityContextHolder.getContext().authentication = upgraded
                request.setAttribute(
                    AdminPrincipal.ATTRIBUTE,
                    AdminPrincipal(identity.id, identity.email, identity.displayName),
                )
            }
        }
        filterChain.doFilter(request, response)
    }

    private companion object {
        const val ROLE_ADMIN = "ROLE_ADMIN"
    }
}
