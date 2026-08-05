package com.todoapp.backend.admin

import com.todoapp.backend.chat.ChatUsageTracker
import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.metrics.MetricsQueryRepository
import com.todoapp.backend.settings.AppSetting
import com.todoapp.backend.settings.AppSettingsService
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.lang.management.ManagementFactory
import java.time.LocalDate
import java.time.ZoneOffset

data class AdminOpsHealth(
    val uptimeSeconds: Long,
    val dbUp: Boolean,
    val dbLatencyMs: Long,
    val serverTime: String,
    val zone: String,
    val recentErrorCount: Int,
    val serverErrors24h: Long?,
    val flags: Map<String, String>,
)

data class AdminChatUsagePoint(
    val date: String,
    val requests: Long,
)

data class AdminChatUsage(
    val zone: String,
    val days: List<AdminChatUsagePoint>,
    val requests: Long,
    val refusals: Long,
    val errors: Long,
    val promptTokens: Long,
    val responseTokens: Long,
    val uniqueUsers: Long,
    /** Live counters from the circuit-breaker itself, so the panel cannot disagree with the gate. */
    val globalDailyUsed: Long,
    val globalDailyLimit: Int,
)

/**
 * Effective, non-secret configuration — whether each optional integration is actually wired.
 *
 * Every one of these fails *silently* when unset: mail becomes a log line, push becomes a no-op, chat
 * returns 503. That is correct behaviour for a local dev run and a production incident nobody notices
 * otherwise, which is exactly why the panel should be able to say plainly which ones are live.
 */
data class AdminOpsConfig(
    val vertexConfigured: Boolean,
    val mailConfigured: Boolean,
    val firebaseConfigured: Boolean,
    val googleOAuthConfigured: Boolean,
    val flags: Map<String, String>,
)

@RestController
@RequestMapping("/admin/ops")
class AdminOpsController(
    private val jdbc: JdbcTemplate,
    private val settings: AppSettingsService,
    private val chatTracker: ChatUsageTracker,
    private val metrics: MetricsQueryRepository,
    private val recentErrors: RecentErrors,
    private val meterRegistry: MeterRegistry,
    private val auditRepository: AdminAuditRepository,
    @org.springframework.beans.factory.annotation.Value("\${app.vertex.project-id:}")
    private val vertexProjectId: String,
    @org.springframework.beans.factory.annotation.Value("\${spring.mail.host:}")
    private val mailHost: String,
    @org.springframework.beans.factory.annotation.Value("\${app.firebase.service-account-path:}")
    private val firebasePath: String,
    @org.springframework.beans.factory.annotation.Value("\${app.oauth.google.client-id:}")
    private val googleClientId: String,
) {

    @GetMapping("/health")
    fun health(): BaseResponse<AdminOpsHealth> {
        val started = System.nanoTime()
        val dbUp = runCatching { jdbc.queryForObject("SELECT 1", Int::class.java) }.isSuccess
        val latencyMs = (System.nanoTime() - started) / 1_000_000

        return BaseResponse.ok(
            AdminOpsHealth(
                uptimeSeconds = ManagementFactory.getRuntimeMXBean().uptime / 1000,
                dbUp = dbUp,
                // Worth watching on a serverless database: a cold compute resuming shows up here as a
                // multi-second ping long before it shows up as a user complaint.
                dbLatencyMs = latencyMs,
                serverTime = java.time.Instant.now().toString(),
                zone = ZONE,
                recentErrorCount = recentErrors.size(),
                serverErrors24h = serverErrorCount(),
                flags = settings.all(),
            ),
        )
    }

    /**
     * Reads the counter Micrometer already maintains for every request. The Prometheus registry is a
     * declared dependency and has been collecting this all along with nothing consuming it — so an
     * error count costs one map lookup rather than a new mechanism.
     *
     * Counts since process start, not a rolling 24 hours; on a service that redeploys several times a
     * week those are usually the same thing, and pretending otherwise would need a time-series store.
     */
    private fun serverErrorCount(): Long? = runCatching {
        meterRegistry.find("http.server.requests")
            .tag("outcome", "SERVER_ERROR")
            .timers()
            .sumOf { it.count() }
    }.getOrNull()

    @GetMapping("/chat-usage")
    fun chatUsage(@RequestParam(defaultValue = "30") days: Int): BaseResponse<AdminChatUsage> {
        val window = days.coerceIn(1, MAX_DAYS)
        val today = LocalDate.now(ZoneOffset.UTC)
        val from = today.minusDays(window - 1L)
        val totals = metrics.chatTotalsBetween(from, today)
        val daily = metrics.dailyChatRequests(from, today)

        return BaseResponse.ok(
            AdminChatUsage(
                zone = ZONE,
                days = generateSequence(from) { it.plusDays(1).takeIf { day -> !day.isAfter(today) } }
                    .map { AdminChatUsagePoint(it.toString(), daily[it] ?: 0L) }
                    .toList(),
                requests = totals.requests,
                refusals = totals.refusals,
                errors = totals.errors,
                promptTokens = totals.promptTokens,
                responseTokens = totals.responseTokens,
                uniqueUsers = totals.uniqueUsers,
                globalDailyUsed = chatTracker.globalDailyUsed(),
                globalDailyLimit = settings.intValue(AppSetting.CHAT_MAX_GLOBAL_DAILY_REQUESTS),
            ),
        )
    }

    @GetMapping("/errors")
    fun errors(@RequestParam(defaultValue = "100") limit: Int): BaseResponse<List<RecentError>> =
        BaseResponse.ok(recentErrors.snapshot(limit.coerceIn(1, MAX_ERRORS)))

    @GetMapping("/config")
    fun config(): BaseResponse<AdminOpsConfig> = BaseResponse.ok(
        AdminOpsConfig(
            // Presence only — never the values. These are credentials and project identifiers.
            vertexConfigured = vertexProjectId.isNotBlank(),
            mailConfigured = mailHost.isNotBlank(),
            firebaseConfigured = firebasePath.isNotBlank(),
            googleOAuthConfigured = googleClientId.isNotBlank(),
            flags = settings.all(),
        ),
    )

    @GetMapping("/audit")
    fun audit(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): BaseResponse<List<AdminAuditEntity>> = BaseResponse.ok(
        auditRepository.findAllByOrderByCreatedAtDesc(
            PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_AUDIT_PAGE)),
        ).content,
    )

    private companion object {
        const val ZONE = "UTC"
        const val MAX_DAYS = 365
        const val MAX_ERRORS = 500
        const val MAX_AUDIT_PAGE = 200
    }
}
