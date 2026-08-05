package com.todoapp.backend.metrics

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Marks the acting user as active for today, once their request has actually been served.
 *
 * Deliberately a [HandlerInterceptor] rather than a servlet filter, and deliberately not hooked into
 * [com.todoapp.backend.auth.JwtAuthFilter]:
 *
 *  - A filter runs *before* authorization, so it would count requests that go on to be rejected —
 *    inflating DAU with failed and forbidden calls.
 *  - `afterCompletion` runs inside the dispatch, while the security context is still populated, so the
 *    acting user is available without re-parsing the token.
 *  - JwtAuthFilter is the single most load-bearing file for the live Android app. Leaving it untouched
 *    was worth more than the convenience of hooking in there.
 *
 * Requests that failed are skipped: an unauthenticated or forbidden call is not evidence that anyone
 * used the product.
 */
class ActivityInterceptor(private val recorder: ActivityRecorder) : HandlerInterceptor {

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        if (ex != null || response.status >= HttpServletResponse.SC_BAD_REQUEST) return
        val userId = SecurityContextHolder.getContext().authentication?.principal as? Long ?: return
        recorder.record(userId)
    }
}
