package com.todoapp.backend.admin

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.settings.AppSetting
import com.todoapp.backend.settings.AppSettingsService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** Body for a settings change. A single field so the payload cannot smuggle in a second key. */
data class UpdateSettingRequest(val value: String)

@RestController
@RequestMapping("/admin/settings")
class AdminSettingsController(
    private val settings: AppSettingsService,
    private val audit: AdminAuditService,
) {

    @GetMapping
    fun list(): BaseResponse<Map<String, String>> = BaseResponse.ok(settings.all())

    /**
     * Changing one of these alters live product behaviour for every user, so two things are
     * non-negotiable: the key must be one of the known switches (never a free-text row insert), and the
     * value must parse as the type that switch expects.
     *
     * Validation matters more than it looks. `chat_enabled` is read with an equals-"true" comparison, so
     * a typo like "ture" would read as *false* and silently kill DoneBot for everyone — a switch flipped
     * by accident in the direction of an outage. Rejecting it here keeps that impossible.
     */
    @PutMapping("/{key}")
    fun update(
        @PathVariable key: String,
        @RequestBody body: UpdateSettingRequest,
    ): BaseResponse<Map<String, String>> {
        val setting = AppSetting.byKey(key)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown setting: $key")
        val value = body.value.trim()
        validate(setting, value)

        settings.update(setting, value, CurrentAdmin.get().id)
        audit.record(
            action = AdminAction.SETTING_UPDATE,
            targetType = "setting",
            targetId = setting.key,
            detail = "value=$value",
        )
        return BaseResponse.ok(settings.all())
    }

    private fun validate(setting: AppSetting, value: String) {
        when (setting) {
            AppSetting.CHAT_MAX_GLOBAL_DAILY_REQUESTS -> {
                val parsed = value.toIntOrNull()
                if (parsed == null || parsed < 0) {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "${setting.key} must be a non-negative integer",
                    )
                }
            }

            else -> if (!value.equals("true", true) && !value.equals("false", true)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "${setting.key} must be true or false")
            }
        }
    }
}
