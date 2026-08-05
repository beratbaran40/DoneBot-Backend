package com.todoapp.backend.settings

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant

/**
 * Reads the operator-tunable settings, cached in memory and refreshed on write.
 *
 * Lives outside the `admin` package on purpose. ChatService and AuthService consume these values, and
 * having the chat and auth paths depend on an admin package would be the wrong dependency direction —
 * the switches are a property of the running service, and the panel is merely one way to change them.
 *
 * **Fail-open, deliberately.** A database blip must not silently disable chat or block every signup, so
 * a failed read falls back to the last known snapshot and, failing that, to the code defaults — which
 * are all "on". The failure mode of this cache is "keeps working", never "turns the product off".
 */
@Service
class AppSettingsService(
    private val repository: AppSettingRepository,
    @Value("\${app.settings.cache-seconds:60}") private val cacheSeconds: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var snapshot: Snapshot? = null

    private data class Snapshot(val at: Instant, val values: Map<String, String>)

    fun isEnabled(setting: AppSetting): Boolean =
        (raw(setting) ?: setting.defaultValue).equals("true", ignoreCase = true)

    fun intValue(setting: AppSetting): Int =
        raw(setting)?.toIntOrNull() ?: setting.defaultValue.toInt()

    fun all(): Map<String, String> =
        AppSetting.entries.associate { it.key to (raw(it) ?: it.defaultValue) }

    @Transactional
    fun update(setting: AppSetting, value: String, actorUserId: Long) {
        val entity = repository.findById(setting.key).orElseGet { AppSettingEntity(settingKey = setting.key) }
        entity.settingValue = value
        entity.updatedAt = Instant.now()
        entity.updatedBy = actorUserId
        repository.save(entity)
        invalidateAfterCommit()
    }

    /**
     * Drops the cache **after** the write commits, not during it.
     *
     * Clearing it inside the transaction opens a window that defeats the whole point of a kill switch:
     * another request arriving between the invalidation and the commit finds an empty cache, reads the
     * still-old committed value, and caches it for a further full TTL. The operator would see chat stay
     * enabled for up to a minute after switching it off — during exactly the kind of incident the switch
     * exists for.
     *
     * It also means a rolled back write leaves no trace, so the cache can never hold a value that was
     * never committed.
     */
    private fun invalidateAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            snapshot = null
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    snapshot = null
                }
            },
        )
    }

    private fun raw(setting: AppSetting): String? = current()[setting.key]

    private fun current(): Map<String, String> {
        val cached = snapshot
        if (cached != null && Duration.between(cached.at, Instant.now()).seconds < cacheSeconds) {
            return cached.values
        }
        return try {
            val fresh = repository.findAll().associate { it.settingKey to it.settingValue }
            snapshot = Snapshot(Instant.now(), fresh)
            fresh
        } catch (e: Exception) {
            log.warn("Failed to read app settings; serving last known values", e)
            cached?.values ?: emptyMap()
        }
    }
}
