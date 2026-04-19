package com.todoapp.backend.notif

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.todoapp.backend.config.FirebaseProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.FileInputStream

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

    fun sendToUsers(userIds: Collection<Long>, title: String, body: String, data: Map<String, String> = emptyMap()) {
        if (!enabled || userIds.isEmpty()) return
        val targets = tokens.findAllByUserIdIn(userIds)
        if (targets.isEmpty()) return
        val messaging = FirebaseMessaging.getInstance()
        targets.forEach { t ->
            try {
                val msg = Message.builder()
                    .setToken(t.token)
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                    .putAllData(data)
                    .build()
                messaging.send(msg)
            } catch (e: Exception) {
                log.warn("FCM send failed for token id=${t.id}: ${e.message}")
            }
        }
    }
}
