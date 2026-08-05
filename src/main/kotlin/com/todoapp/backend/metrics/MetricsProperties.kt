package com.todoapp.backend.metrics

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.metrics")
class MetricsProperties {

    var activity: Activity = Activity()

    /**
     * How long the overview payload is served from memory. The panel deliberately does not auto-refresh,
     * but this still bounds the damage if a tab is left open somewhere hammering refresh — and on Neon
     * every avoided query is avoided compute time.
     */
    var overviewCacheSeconds: Long = 60

    class Activity {
        /**
         * Kill switch for activity recording. Turning it off stops the writes but leaves already
         * collected history intact, so the panel keeps working with a frozen "today".
         */
        var enabled: Boolean = true
    }
}
