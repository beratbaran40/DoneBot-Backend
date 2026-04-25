package com.todoapp.backend.notif

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.todoapp.backend.config.FirebaseProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.FileInputStream

data class PushResult(
    val delivered: Int,
    val failed: Int,
    val deadTokensRemoved: Int,
)

@Service
class PushService(
    private val tokens: DeviceTokenRepository,
    private val props: FirebaseProperties,
) {
    private val log = LoggerFactory.getLogger(PushService::class.java)
    private var enabled = false

    @PostConstruct
    fun init() {
        val path = props.serviceAccountPath
        if (path.isBlank()) {
            log.warn("FCM disabled: app.firebase.service-account-path is not set")
            return
        }
        try {
            FileInputStream(path).use { stream ->
                val opts = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(stream))
                    .build()
                if (FirebaseApp.getApps().isEmpty()) FirebaseApp.initializeApp(opts)
            }
            enabled = true
            log.info("FCM enabled")
        } catch (e: Exception) {
            log.warn("FCM init failed: ${e.message}")
        }
    }

    /**
     * Send a data-only push. The client is responsible for rendering a notification from the data
     * payload. Returns per-user delivery summary; dead tokens (UNREGISTERED / INVALID_ARGUMENT)
     * are deleted from the device_tokens table.
     */
    fun sendDataOnly(userIds: Collection<Long>, data: Map<String, String>): PushResult {
        if (!enabled || userIds.isEmpty()) return PushResult(0, 0, 0)
        val targets = tokens.findAllByUserIdIn(userIds)
        if (targets.isEmpty()) return PushResult(0, 0, 0)
        val messaging = FirebaseMessaging.getInstance()
        var delivered = 0
        var failed = 0
        var deadRemoved = 0
        targets.forEach { t ->
            val msg = Message.builder()
                .setToken(t.token)
                .putAllData(data)
                .setAndroidConfig(
                    AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .build(),
                )
                .build()
            try {
                messaging.send(msg)
                delivered++
            } catch (e: FirebaseMessagingException) {
                failed++
                when (e.messagingErrorCode) {
                    MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT -> {
                        runCatching { tokens.delete(t) }
                            .onSuccess {
                                deadRemoved++
                                log.info("Removed dead token id=${t.id} userId=${t.userId} reason=${e.messagingErrorCode}")
                            }
                    }

                    else -> log.warn(
                        "FCM send failed token id=${t.id} userId=${t.userId} code=${e.messagingErrorCode}: ${e.message}",
                    )
                }
            } catch (e: Exception) {
                failed++
                log.warn("FCM send failed token id=${t.id} userId=${t.userId}: ${e.message}")
            }
        }
        return PushResult(delivered, failed, deadRemoved)
    }
}
