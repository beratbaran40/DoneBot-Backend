-- V25: runtime settings the operator can change from the panel without a redeploy.
--
-- These are deliberately NOT generic client-facing feature flags — the Android app reads none of them.
-- They are server-enforced kill switches, which is what actually matters operationally: if Vertex spend
-- spikes at 2am, `chat_enabled=false` stops it on the next request, whereas a client flag would need a
-- Play release and would still leave old installs spending.
--
-- chat_max_global_daily_requests moves the existing app.vertex.max-global-daily-requests property in
-- here for the same reason: dialling 5000 down to 200 should be a click, not a deploy that takes the
-- API down for two minutes while it rebuilds.
--
-- ⚠️ `key` and `value` are BOTH reserved words in H2 2.x — `CREATE TABLE app_settings (key ...)` fails
-- to parse and takes every test's application context down with it. Hence setting_key/setting_value.

CREATE TABLE IF NOT EXISTS app_settings (
    setting_key   VARCHAR(64)  PRIMARY KEY,
    setting_value VARCHAR(256) NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- No FK: an audit-shaped column that must survive deletion of the admin who set it.
    updated_by    BIGINT
);

-- Seeded to the current effective behaviour, so applying this migration changes nothing at runtime.
-- AppSettingsService also carries code-level defaults, so a missing row is never a functional change.
INSERT INTO app_settings (setting_key, setting_value) VALUES ('chat_enabled', 'true');
INSERT INTO app_settings (setting_key, setting_value) VALUES ('registration_enabled', 'true');
INSERT INTO app_settings (setting_key, setting_value) VALUES ('push_enabled', 'true');
INSERT INTO app_settings (setting_key, setting_value) VALUES ('chat_max_global_daily_requests', '5000');
