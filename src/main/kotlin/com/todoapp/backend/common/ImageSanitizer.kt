package com.todoapp.backend.common

import org.springframework.http.HttpStatus
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Re-encoded raster bytes + the normalized content-type to store and serve. */
data class SanitizedImage(val bytes: ByteArray, val contentType: String)

/**
 * Validates and re-encodes an uploaded image so only a genuine raster is ever stored.
 *
 * The avatar/photo GET endpoints are public and serve the stored bytes back with the stored
 * content-type, so an `image/svg+xml` (a valid image type but executable — embedded `<script>`) would
 * be a stored-XSS vector. This: (1) narrows the allowlist to JPEG/PNG (rejects svg/gif); (2) decodes
 * with ImageIO — anything it can't decode (svg/corrupt) is rejected; (3) re-encodes, which drops any
 * embedded payload. Callers enforce their own size caps BEFORE calling this (don't decode huge files).
 */
object ImageSanitizer {
    private val ALLOWED = setOf("image/jpeg", "image/png")

    fun sanitize(file: MultipartFile): SanitizedImage {
        val declared = (file.contentType ?: "").lowercase()
        if (declared !in ALLOWED) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unsupported image type — only JPEG and PNG are allowed",
            )
        }
        val decoded = runCatching { file.inputStream.use { ImageIO.read(it) } }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File is not a valid image")
        val format = if (declared == "image/png") "png" else "jpeg"
        // JPEG can't carry an alpha channel; flatten onto white so an alpha PNG mislabeled as JPEG
        // doesn't make the writer throw or emit garbled colors.
        val toWrite = if (format == "jpeg" && decoded.colorModel.hasAlpha()) {
            BufferedImage(decoded.width, decoded.height, BufferedImage.TYPE_INT_RGB).also { rgb ->
                rgb.createGraphics().apply {
                    drawImage(decoded, 0, 0, Color.WHITE, null)
                    dispose()
                }
            }
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(toWrite, format, out) || out.size() == 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not process image")
        }
        return SanitizedImage(out.toByteArray(), "image/$format")
    }
}
