package com.todoapp.backend.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Tags each request with a correlation id in the SLF4J MDC (`requestId`), so the log pattern
 * (`%X{requestId}`) stamps every line of one request identically — making a user's complaint
 * traceable in prod logs without heavyweight tracing. Reuses an inbound `X-Request-Id` (e.g. from a
 * proxy) when present, otherwise generates a short one, and echoes it on the response. Runs first
 * (HIGHEST_PRECEDENCE) so even auth failures are tagged; always clears the MDC in `finally`.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(HEADER)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().take(8)
        MDC.put(KEY, requestId)
        response.setHeader(HEADER, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(KEY)
        }
    }

    private companion object {
        const val HEADER = "X-Request-Id"
        const val KEY = "requestId"
    }
}
