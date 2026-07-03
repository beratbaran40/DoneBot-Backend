package com.todoapp.backend.integration

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Locks §4.15: an svg (or any non-raster) avatar upload is rejected, and an accepted image is stored
 * as a re-encoded raster — so the PUBLIC avatar GET can never serve back an executable content-type
 * (stored-XSS). Covers the avatar path; the same ImageSanitizer guards group avatars + task photos.
 */
class ImageUploadIntegrationTest : AbstractIntegrationTest() {
    @Test
    fun `an svg avatar upload is rejected`() {
        val user = registerUser()
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""".toByteArray()
        mockMvc
            .perform(
                multipart("/users/me/avatar")
                    .file(MockMultipartFile("file", "x.svg", "image/svg+xml", svg))
                    .header("Authorization", bearer(user.user.id)),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `a png avatar is accepted and stored as a decodable raster`() {
        val user = registerUser()
        mockMvc
            .perform(
                multipart("/users/me/avatar")
                    .file(MockMultipartFile("file", "x.png", "image/png", onePixelPng()))
                    .header("Authorization", bearer(user.user.id)),
            ).andExpect(status().isOk)

        // The public GET serves the STORED bytes back — they must decode as a real raster (re-encoded).
        val stored = mockMvc
            .perform(get("/users/${user.user.id}/avatar"))
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsByteArray
        assertNotNull(
            ImageIO.read(ByteArrayInputStream(stored)),
            "stored avatar bytes must decode as a raster image",
        )
    }

    private fun onePixelPng(): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", out)
        return out.toByteArray()
    }
}
