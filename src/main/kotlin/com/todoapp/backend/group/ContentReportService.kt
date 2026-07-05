package com.todoapp.backend.group

import com.todoapp.backend.auth.MailService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest

/**
 * Persists user reports of offensive/inappropriate group content (member / shared photo / task) for
 * manual moderation review. Backs the in-app "Report" action required by Google Play's UGC policy.
 * De-duplicates per (reporter, target) and best-effort notifies the admin inbox. Membership of the
 * reporter is enforced by the caller (GroupController via GroupService.requireMember).
 */
@Service
class ContentReportService(
    private val contentReportRepository: ContentReportRepository,
    private val mailService: MailService,
) {
    private val log = LoggerFactory.getLogger(ContentReportService::class.java)

    fun record(reporterUserId: Long, groupId: Long, request: ReportContentRequest) {
        val targetType = request.targetType.trim().uppercase()
        if (targetType !in ALLOWED_TYPES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown report target type")
        }
        val targetKey = request.targetUserId?.toString()
            ?: request.targetRef?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Report target is missing")
        val hash = sha256Hex("$groupId:$targetType:$targetKey")

        if (contentReportRepository.existsByReporterUserIdAndTargetHash(reporterUserId, hash)) {
            log.info("content_report duplicate ignored reporter={} group={}", reporterUserId, groupId)
            return
        }
        try {
            contentReportRepository.save(
                ContentReportEntity(
                    reporterUserId = reporterUserId,
                    groupId = groupId,
                    targetType = targetType,
                    targetUserId = request.targetUserId,
                    targetRef = request.targetRef?.takeIf { it.isNotBlank() },
                    reason = request.reason?.takeIf { it.isNotBlank() },
                    targetHash = hash,
                ),
            )
        } catch (ex: DataIntegrityViolationException) {
            // Lost a race with a concurrent identical report — already recorded, treat as success.
            log.info("content_report duplicate race ignored reporter={} group={}", reporterUserId, groupId)
            return
        }
        log.info(
            "content_report stored reporter={} group={} type={} reasonPresent={}",
            reporterUserId, groupId, targetType, request.reason != null,
        )
        notifyAdmin(reporterUserId, groupId, targetType, targetKey)
    }

    /** Best-effort admin heads-up. Never fails the report if mail is down or disabled. */
    private fun notifyAdmin(reporterUserId: Long, groupId: Long, targetType: String, targetKey: String) {
        val summary = "UGC report — type=$targetType group=$groupId target=$targetKey"
        runCatching { mailService.sendReportNotification(reporterUserId, summary) }
            .onFailure { log.warn("content_report admin email failed (report already saved)", it) }
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val ALLOWED_TYPES = setOf("MEMBER", "PHOTO", "TASK")
    }
}
