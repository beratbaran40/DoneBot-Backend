package com.todoapp.backend.common

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * Renders a string as a self-contained SVG QR code — crisp at any size, no external image request.
 *
 * Used by the desktop password-reset landing page so a user on a computer can scan-to-continue on
 * their phone. Generated server-side so the one-time reset token never leaves our infrastructure
 * (unlike a third-party chart/QR service). The token is encoded into the module geometry, not as
 * text in the markup, so nothing user-controlled is reflected into the page.
 */
object QrSvg {

    fun render(
        content: String,
        quietZone: Int = 4,
        darkColor: String = "#090E23",
        lightColor: String = "#FFFFFF",
    ): String {
        val matrix = Encoder.encode(
            content,
            ErrorCorrectionLevel.M,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"),
        ).matrix ?: return ""

        val width = matrix.width
        val height = matrix.height
        val side = width + quietZone * 2

        val path = StringBuilder()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (matrix.get(x, y).toInt() == 1) {
                    // One 1x1 module rect, offset into the quiet zone, as a compact path command.
                    path.append("M").append(x + quietZone).append(' ').append(y + quietZone).append("h1v1h-1z")
                }
            }
        }

        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
            append(side).append(' ').append(side)
            append("\" width=\"100%\" height=\"100%\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"QR code\">")
            append("<rect width=\"").append(side).append("\" height=\"").append(side)
            append("\" fill=\"").append(lightColor).append("\"/>")
            append("<path fill=\"").append(darkColor).append("\" d=\"").append(path).append("\"/>")
            append("</svg>")
        }
    }
}
