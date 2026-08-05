package com.todoapp.backend.settings

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * The operator-tunable settings, each with the value that applies when the database says nothing.
 *
 * The code default is the safety net: a missing row, an unreadable database, or a fresh environment
 * that never ran the seed must all behave exactly like production does today. Every default here is
 * therefore the current shipped behaviour — enabling this feature changes nothing until someone
 * deliberately flips a switch.
 *
 * These are **server-enforced** switches, not client feature flags. The Android app reads none of them,
 * which is the point: `chat_enabled = false` stops Vertex spend on the very next request, whereas a
 * client-side flag would need a Play release and would still leave every existing install spending.
 */
enum class AppSetting(val key: String, val defaultValue: String) {
    /** Kill switch for DoneBot. Turning it off degrades chat to the same 503 the client already handles. */
    CHAT_ENABLED("chat_enabled", "true"),

    /** Blocks new account creation — both email signup and first-time Google sign-in. */
    REGISTRATION_ENABLED("registration_enabled", "true"),

    /** Master switch for outbound push, above each user's own preference. */
    PUSH_ENABLED("push_enabled", "true"),

    /**
     * The global daily ceiling the chat cost circuit-breaker compares against. Lives here rather than in
     * application.properties so it can be dialled from 5000 down to 200 during a spend incident without
     * a redeploy — which on Render means a container rebuild and a minutes-long gap.
     */
    CHAT_MAX_GLOBAL_DAILY_REQUESTS("chat_max_global_daily_requests", "5000"),
    ;

    companion object {
        fun byKey(key: String): AppSetting? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Column names are `setting_key`/`setting_value` because `key` and `value` are both reserved words in
 * H2 2.x — naming them the obvious way makes the schema fail to parse on every developer machine and in
 * every test, while working fine on production Postgres.
 */
@Entity
@Table(name = "app_settings")
class AppSettingEntity(
    @Id
    @Column(name = "setting_key", length = 64)
    var settingKey: String = "",

    @Column(name = "setting_value", nullable = false, length = 256)
    var settingValue: String = "",

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "updated_by", nullable = true)
    var updatedBy: Long? = null,
)

@Repository
interface AppSettingRepository : JpaRepository<AppSettingEntity, String>
