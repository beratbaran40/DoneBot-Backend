package com.todoapp.backend.admin

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.metrics.AdminBreakdown
import com.todoapp.backend.metrics.AdminFunnel
import com.todoapp.backend.metrics.AdminOverview
import com.todoapp.backend.metrics.AdminRetention
import com.todoapp.backend.metrics.AdminTimeSeries
import com.todoapp.backend.metrics.MetricsService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

@RestController
@RequestMapping("/admin/metrics")
class AdminMetricsController(private val metrics: MetricsService) {

    @GetMapping("/overview")
    fun overview(response: HttpServletResponse): BaseResponse<AdminOverview> {
        response.applyShortCache()
        return BaseResponse.ok(metrics.overview())
    }

    /**
     * All series in one payload rather than one request per chart: the panel's overview draws five of
     * them, and five separate round-trips would mean five chances to wake a suspended database compute.
     */
    @GetMapping("/timeseries")
    fun timeSeries(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        response: HttpServletResponse,
    ): BaseResponse<AdminTimeSeries> {
        val end = to.parseDateOrDefault(LocalDate.now(ZoneOffset.UTC))
        val start = from.parseDateOrDefault(end.minusDays(DEFAULT_WINDOW_DAYS))
        if (start.isAfter(end)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must not be after 'to'")
        }
        // Bounded so a hand-edited URL cannot ask for a decade and gap-fill 3650 points per series.
        if (start.plusDays(MAX_WINDOW_DAYS).isBefore(end)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Window may not exceed $MAX_WINDOW_DAYS days")
        }
        response.applyShortCache()
        return BaseResponse.ok(metrics.timeSeries(start, end))
    }

    @GetMapping("/breakdown")
    fun breakdown(
        @RequestParam dimension: String,
        response: HttpServletResponse,
    ): BaseResponse<AdminBreakdown> {
        // Closed set, checked here rather than passed through: the dimension selects a query, and an
        // unchecked string reaching query selection is how injection bugs start.
        if (dimension !in ALLOWED_DIMENSIONS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unknown dimension; expected one of $ALLOWED_DIMENSIONS",
            )
        }
        response.applyShortCache()
        return BaseResponse.ok(metrics.breakdown(dimension))
    }

    @GetMapping("/retention")
    fun retention(
        @RequestParam(defaultValue = "12") cohortWeeks: Int,
        response: HttpServletResponse,
    ): BaseResponse<AdminRetention> {
        val weeks = cohortWeeks.coerceIn(1, MAX_COHORT_WEEKS)
        response.applyShortCache()
        return BaseResponse.ok(metrics.retention(weeks))
    }

    @GetMapping("/funnel")
    fun funnel(
        @RequestParam(defaultValue = "30") windowDays: Int,
        response: HttpServletResponse,
    ): BaseResponse<AdminFunnel> {
        val window = windowDays.coerceIn(1, MAX_WINDOW_DAYS.toInt())
        response.applyShortCache()
        return BaseResponse.ok(metrics.funnel(window))
    }

    /**
     * Lets a browser refresh, or a second tab, reuse the response instead of re-querying. Private
     * because the payload is operator-only and must never sit in a shared proxy cache.
     */
    private fun HttpServletResponse.applyShortCache() {
        setHeader("Cache-Control", "private, max-age=30")
    }

    private fun String?.parseDateOrDefault(fallback: LocalDate): LocalDate {
        if (this.isNullOrBlank()) return fallback
        return try {
            LocalDate.parse(this)
        } catch (_: DateTimeParseException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Dates must be ISO yyyy-MM-dd")
        }
    }

    private companion object {
        const val DEFAULT_WINDOW_DAYS = 29L
        const val MAX_WINDOW_DAYS = 365L
        const val MAX_COHORT_WEEKS = 52
        val ALLOWED_DIMENSIONS = setOf("category", "provider", "recurrence", "groupSize")
    }
}
