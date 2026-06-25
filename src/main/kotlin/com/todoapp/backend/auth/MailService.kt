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
    @Value("\${app.mail.asset-base-url:https://donebot-backend.onrender.com}") private val assetBaseUrl: String,
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

    /** Minimal HTML-escape for user-controlled text (e.g. display name) interpolated into emails. */
    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun buildOAuthNoticeHtml(displayName: String, provider: String): String = """
        <!doctype html>
        <html><body style="font-family: -apple-system, Segoe UI, Roboto, sans-serif; padding: 24px; color: #090E23;">
        <h2 style="color:#4566EC;">Use $provider to sign in</h2>
        <p>Hi ${esc(displayName)},</p>
        <p>We received a request to reset the password for your DoneBot account. Your account signs in
        with <strong>$provider</strong>, so there is no password to reset.</p>
        <p>Just open DoneBot and tap <strong>Continue with $provider</strong> on the sign-in screen.</p>
        <p style="color:#7A9CC6;font-size:12px;">If you didn't request this, you can safely ignore this email.</p>
        </body></html>
    """.trimIndent()

    private fun buildHtml(displayName: String, resetLink: String): String {
        val avatar = "$assetBaseUrl/assets/donebot-avatar.png"
        return """
        <!doctype html>
        <html lang="en"><body style="margin:0;padding:0;background:#F8F9FC;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#090E23;">
        <div style="max-width:480px;margin:0 auto;padding:32px 20px;">
          <div style="background:#FFFFFF;border:1px solid #ECEFF6;border-radius:16px;padding:32px 28px;">
            <div style="width:88px;height:88px;margin:0 auto 16px;background:#EFF2FF;border-radius:50%;text-align:center;line-height:88px;">
              <img src="$avatar" width="56" height="56" alt="DoneBot" style="vertical-align:middle;">
            </div>
            <h1 style="margin:0;font-size:22px;color:#4566EC;text-align:center;">Reset your password</h1>
            <p style="margin:6px 0 24px;font-size:13px;color:#7A9CC6;text-align:center;">DoneBot account security</p>
            <p style="margin:0 0 12px;font-size:15px;line-height:1.5;">Hi ${esc(displayName)} &#128075;</p>
            <p style="margin:0 0 28px;font-size:15px;line-height:1.6;color:#3D6A9E;">
              We got a request to reset your DoneBot password. No worries &mdash; tap the button below
              to set a new one. For your security this link works for <strong>30 minutes</strong> and
              can be used <strong>once</strong>.
            </p>
            <div style="text-align:center;margin:0 0 28px;">
              <a href="$resetLink" style="display:inline-block;background:#4566EC;color:#ffffff;padding:14px 26px;border-radius:10px;text-decoration:none;font-size:16px;font-weight:600;">
                Reset password<img src="$avatar" width="20" height="20" alt="" style="vertical-align:middle;margin-left:8px;background:#ffffff;border-radius:50%;">
              </a>
            </div>
            <p style="margin:0;font-size:12px;line-height:1.5;color:#7A9CC6;">
              If the button doesn't work, copy and paste this link into your browser:<br>
              <a href="$resetLink" style="color:#4566EC;word-break:break-all;">$resetLink</a>
            </p>
          </div>
          <p style="margin:20px 0 0;font-size:12px;line-height:1.5;color:#9AA7BD;text-align:center;">
            You're receiving this because a password reset was requested for your DoneBot account.
            If that wasn't you, you can safely ignore this email &mdash; your password stays the same.
          </p>
          <p style="margin:10px 0 0;font-size:12px;color:#B6C0D4;text-align:center;">&copy; DoneBot</p>
        </div>
        </body></html>
        """.trimIndent()
    }
}
