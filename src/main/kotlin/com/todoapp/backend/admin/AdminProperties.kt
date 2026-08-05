package com.todoapp.backend.admin

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Admin-surface configuration. Picked up by @ConfigurationPropertiesScan on the application class.
 *
 * [allowedEmails] is the second of the three gates guarding the /admin endpoints (the others being
 * `users.role = ADMIN` and `users.status = ACTIVE`). It exists so that flipping a database column —
 * by accident, by a bad migration, or by an attacker who reached the DB — is not by itself enough to
 * grant access to a surface that can delete accounts.
 *
 * It is deliberately **fail-closed**: a blank value denies everyone rather than allowing everyone.
 * Forgetting the env var locks you out of the panel; the inverse mistake would open it to every user.
 */
@ConfigurationProperties(prefix = "app.admin")
class AdminProperties {

    /** Comma-separated exact email addresses. Compared case-insensitively after trimming. */
    var allowedEmails: String = ""

    var cors: Cors = Cors()

    class Cors {
        /**
         * Comma-separated exact browser origins allowed to call the panel's endpoints, e.g.
         * `https://donebot-admin.vercel.app`. Exact origins only — no wildcard patterns. Vercel mints a
         * fresh origin per preview deployment, so previews will not work against this backend; that is
         * the intended trade rather than opening up `*.vercel.app`.
         */
        var allowedOrigins: String = "http://localhost:5173"
    }

    fun corsOrigins(): List<String> =
        cors.allowedOrigins.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun isAllowed(email: String): Boolean {
        // users.email is a plain VARCHAR (not citext), so normalise both sides rather than trusting
        // that the stored value is already lowercase.
        val candidate = email.trim().lowercase()
        if (candidate.isEmpty()) return false
        return allowedEmails.splitToSequence(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .any { it == candidate }
    }
}
