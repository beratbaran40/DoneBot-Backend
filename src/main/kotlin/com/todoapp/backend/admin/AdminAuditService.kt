package com.todoapp.backend.admin

import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * Writes the audit trail for administrative actions.
 *
 * Runs in the **caller's** transaction on purpose: call it after the action has succeeded, so a rolled
 * back action rolls back its audit record too. An audit log that claims an account was deleted when the
 * delete failed is worse than no log at all.
 */
@Service
class AdminAuditService(private val repository: AdminAuditRepository) {

    @Transactional
    fun record(
        action: String,
        targetType: String? = null,
        targetId: String? = null,
        detail: String? = null,
    ) {
        val admin = CurrentAdmin.get()
        repository.save(
            AdminAuditEntity(
                actorUserId = admin.id,
                actorEmail = admin.email,
                action = action,
                targetType = targetType,
                targetId = targetId,
                // Detail is free text assembled by callers and may quote user-supplied reasons; the
                // column is VARCHAR(1000), so clamp rather than let a long reason fail the insert.
                detail = detail?.take(DETAIL_MAX_LENGTH),
                requestId = MDC.get(REQUEST_ID_KEY),
                ip = clientIp(),
            ),
        )
    }

    /**
     * Behind Render's proxy `server.forward-headers-strategy=framework` makes Spring resolve the real
     * client address from the forwarded headers, so remoteAddr is already the caller's IP in prod.
     */
    private fun clientIp(): String? {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        return attributes?.request?.remoteAddr?.take(IP_MAX_LENGTH)
    }

    private companion object {
        const val REQUEST_ID_KEY = "requestId"
        const val DETAIL_MAX_LENGTH = 1000
        const val IP_MAX_LENGTH = 64
    }
}
