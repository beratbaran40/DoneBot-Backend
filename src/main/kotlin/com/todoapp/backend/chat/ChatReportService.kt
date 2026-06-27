package com.todoapp.backend.chat

import com.todoapp.backend.auth.MailService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.security.MessageDigest

/**
 * Persists user reports of offensive/inappropriate DoneBot replies for manual moderation
 * review. Backs the in-app "report this response" action required by Google Play's Generative
 * AI content policy. De-duplicates per (user, reply) and best-effort notifies the admin inbox.
 */
@Service
class ChatReportService(
    private val chatReportRepository: ChatReportRepository,
    private val mailService: MailService,
) {
    private val log = LoggerFactory.getLogger(ChatReportService::class.java)

    fun record(userId: Long, request: ChatReportRequest) {
        val hash = sha256Hex(request.messageContent)
        if (chatReportRepository.existsByUserIdAndMessageHash(userId, hash)) {
            log.info("chat_report duplicate ignored user={}", userId)
            return
        }
        try {
            chatReportRepository.save(
                ChatReportEntity(
                    userId = userId,
                    messageContent = request.messageContent,
                    messageHash = hash,
                    reason = request.reason?.takeIf { it.isNotBlank() },
                ),
            )
        } catch (ex: DataIntegrityViolationException) {
            // Lost a race with a concurrent identical report — already recorded, treat as success.
            log.info("chat_report duplicate race ignored user={}", userId)
            return
        }
        log.info("chat_report stored user={} reasonPresent={}", userId, request.reason != null)
        notifyAdmin(userId, request.messageContent)
    }

    /** Best-effort admin heads-up. Never fails the report if mail is down or disabled. */
    private fun notifyAdmin(userId: Long, messageContent: String) {
        runCatching { mailService.sendReportNotification(userId, messageContent) }
            .onFailure { log.warn("chat_report admin email failed (report already saved)", it) }
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
