package com.todoapp.backend.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service

/**
 * Sends the password-reset email. If `spring.mail.host` is blank (dev / no SMTP configured)
 * the link is only logged — the forgot-password endpoint still returns 200 so the client
 * flow can be exercised end-to-end without live SMTP.
 */
@Service
class MailService(
    private val mailSender: JavaMailSender,
    @Value("\${spring.mail.host:}") private val host: String,
    @Value("\${app.mail.from}") private val from: String,
    @Value("\${app.mail.from-name}") private val fromName: String,
) {
    private val log = LoggerFactory.getLogger(MailService::class.java)

    fun sendPasswordReset(toEmail: String, displayName: String, resetLink: String) {
        if (host.isBlank()) {
            log.warn("[MAIL:DEV] password reset for {} (name={}) link={}", toEmail, displayName, resetLink)
            return
        }
        val message = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, true, "UTF-8")
        helper.setFrom(from, fromName)
        helper.setTo(toEmail)
        helper.setSubject("Reset your DoneBot password")
        helper.setText(buildHtml(displayName, resetLink), true)
        mailSender.send(message)
    }

    /**
     * Sent when someone requests a password reset for a social-only (OAuth) account. There is no
     * password to reset, so we guide the real inbox owner to their sign-in provider instead.
     */
    fun sendOAuthAccountNotice(toEmail: String, displayName: String, providersCsv: String) {
        if (host.isBlank()) {
            log.warn("[MAIL:DEV] oauth-account notice for {} (providers={})", toEmail, providersCsv)
            return
        }
        val provider = providerLabel(providersCsv)
        val message = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, true, "UTF-8")
        helper.setFrom(from, fromName)
        helper.setTo(toEmail)
        helper.setSubject("About your DoneBot password reset request")
        helper.setText(buildOAuthNoticeHtml(displayName, provider), true)
        mailSender.send(message)
    }

    private fun providerLabel(providersCsv: String): String {
        val provider = providersCsv.split(",")
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && it != "email" }
            ?.lowercase()
        return when (provider) {
            "google" -> "Google"
            else -> "a social account"
        }
    }

    private fun buildOAuthNoticeHtml(displayName: String, provider: String): String = """
        <!doctype html>
        <html><body style="font-family: -apple-system, Segoe UI, Roboto, sans-serif; padding: 24px; color: #090E23;">
        <h2 style="color:#4566EC;">Use $provider to sign in</h2>
        <p>Hi $displayName,</p>
        <p>We received a request to reset the password for your DoneBot account. Your account signs in
        with <strong>$provider</strong>, so there is no password to reset.</p>
        <p>Just open DoneBot and tap <strong>Continue with $provider</strong> on the sign-in screen.</p>
        <p style="color:#7A9CC6;font-size:12px;">If you didn't request this, you can safely ignore this email.</p>
        </body></html>
    """.trimIndent()

    private fun buildHtml(displayName: String, resetLink: String): String = """
        <!doctype html>
        <html><body style="font-family: -apple-system, Segoe UI, Roboto, sans-serif; padding: 24px; color: #090E23;">
        <h2 style="color:#4566EC;">Reset your password</h2>
        <p>Hi $displayName,</p>
        <p>We received a request to reset your DoneBot password. Tap the button below to choose a new one.
        This link is valid for 30 minutes and can only be used once.</p>
        <p><a href="$resetLink" style="display:inline-block;background:#4566EC;color:#fff;padding:12px 20px;border-radius:8px;text-decoration:none;">Reset password</a></p>
        <p style="color:#7A9CC6;font-size:12px;">If the button doesn't work, copy and paste this link into your browser:<br>$resetLink</p>
        <p style="color:#7A9CC6;font-size:12px;">If you didn't request this, you can safely ignore this email.</p>
        </body></html>
    """.trimIndent()
}
