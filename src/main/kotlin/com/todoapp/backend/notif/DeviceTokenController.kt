package com.todoapp.backend.notif

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.common.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class FcmTokenRequest(
    @field:NotBlank val token: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
)

data class FcmTokenResponseData(
    val token: String,
    val deviceId: String?,
    val deviceName: String?,
)

@Service
class DeviceTokenService(private val tokens: DeviceTokenRepository) {
    @Transactional
    fun upsert(userId: Long, req: FcmTokenRequest): FcmTokenResponseData {
        val existing = tokens.findByToken(req.token)
        val saved = if (existing != null) {
            existing.userId = userId
            existing.deviceId = req.deviceId ?: existing.deviceId
            existing.deviceName = req.deviceName ?: existing.deviceName
            existing.updatedAt = Instant.now()
            tokens.save(existing)
        } else {
            tokens.save(
                DeviceTokenEntity(
                    userId = userId,
                    token = req.token,
                    deviceId = req.deviceId,
                    deviceName = req.deviceName,
                )
            )
        }
        return FcmTokenResponseData(saved.token, saved.deviceId, saved.deviceName)
    }
}

@RestController
@RequestMapping("/devices")
class DeviceTokenController(private val service: DeviceTokenService) {

    @PostMapping("/fcm-token")
    fun upsertToken(@Valid @RequestBody req: FcmTokenRequest): BaseResponse<FcmTokenResponseData> =
        BaseResponse.ok(service.upsert(CurrentUser.id(), req))
}
