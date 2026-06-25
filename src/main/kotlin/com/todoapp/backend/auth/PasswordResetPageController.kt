package com.todoapp.backend.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Serves the HTTPS password-reset landing page that the reset email links to.
 *
 * Why this exists: the actual reset UI lives in the Android app and is reachable only via the
 * `todoapp://reset-password?token=…` custom-scheme deep link. Email clients (Gmail, Outlook, …)
 * strip/ignore non-http(s) hrefs for security, so a button pointing straight at `todoapp://…`
 * renders but isn't tappable. This page is a plain https URL that email clients DO linkify; when
 * opened it relaunches the app via the deep link (custom schemes work from a real browser page),
 * and shows a manual "Open DoneBot" button + guidance as a fallback.
 */
@RestController
class PasswordResetPageController(
    @Value("\${app.password-reset.deep-link}") private val resetDeepLink: String,
) {

    @GetMapping("/reset-password", produces = [MediaType.TEXT_HTML_VALUE])
    fun page(@RequestParam(name = "token", required = false) token: String?): ResponseEntity<String> {
        val safeToken = token?.takeIf { TOKEN_PATTERN.matches(it) }
        val body = if (safeToken == null) invalidPage() else launcherPage(buildDeepLink(safeToken))
        return ResponseEntity.ok()
            .contentType(MediaType.valueOf("text/html; charset=UTF-8"))
            .header("Cache-Control", "no-store") // one-time token → never cache
            .header("X-Content-Type-Options", "nosniff")
            .body(body)
    }

    private fun buildDeepLink(token: String): String {
        val separator = if (resetDeepLink.contains("?")) "&" else "?"
        return "$resetDeepLink${separator}token=$token"
    }

    private fun launcherPage(deepLink: String): String {
        val href = escapeHtml(deepLink)
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="robots" content="noindex">
              <title>Reset your DoneBot password</title>
            </head>
            <body style="margin:0;font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#F8F9FC;color:#090E23;">
              <div style="max-width:480px;margin:0 auto;padding:48px 24px;text-align:center;">
                <h1 style="color:#4566EC;font-size:22px;margin:0 0 12px;">Reset your password</h1>
                <p style="font-size:15px;line-height:1.5;color:#3D6A9E;margin:0 0 28px;">
                  Tap the button below to open DoneBot and choose a new password.
                </p>
                <a id="open" href="$href"
                   style="display:inline-block;background:#4566EC;color:#fff;padding:14px 28px;border-radius:10px;text-decoration:none;font-size:16px;font-weight:600;">
                  Open DoneBot
                </a>
                <p style="font-size:13px;line-height:1.5;color:#7A9CC6;margin:28px 0 0;">
                  Nothing happened? Open this link on the phone where DoneBot is installed, then tap
                  <strong>Open DoneBot</strong> again. This link is valid for 30 minutes and can only
                  be used once.
                </p>
              </div>
              <script>
                // Custom-scheme navigation is allowed from a real browser page (unlike email
                // clients), so relaunch the app automatically. Reading the already-escaped href off
                // the anchor avoids re-embedding the token in a JS string literal.
                (function () {
                  var a = document.getElementById('open');
                  if (a) { window.location.href = a.href; }
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun invalidPage(): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta name="robots" content="noindex">
          <title>Reset link problem</title>
        </head>
        <body style="margin:0;font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#F8F9FC;color:#090E23;">
          <div style="max-width:480px;margin:0 auto;padding:48px 24px;text-align:center;">
            <h1 style="color:#4566EC;font-size:22px;margin:0 0 12px;">This reset link looks invalid</h1>
            <p style="font-size:15px;line-height:1.5;color:#3D6A9E;margin:0;">
              The link may be incomplete or has expired. Open DoneBot and request a new
              password-reset email from the sign-in screen.
            </p>
          </div>
        </body>
        </html>
    """.trimIndent()

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    companion object {
        // URL-safe Base64 token charset (see AuthService.randomToken). Validating the
        // (attacker-controllable) query param here means it can never break out of the href / JS
        // contexts on the rendered page — kills reflected-XSS on this public endpoint.
        private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{1,512}$")
    }
}
