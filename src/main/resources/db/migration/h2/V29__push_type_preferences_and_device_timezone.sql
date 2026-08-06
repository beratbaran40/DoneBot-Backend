-- V29: per-type push preferences, and the device time zone the due-soon job needs.
--
-- push_disabled_types is a CSV of NotificationType names the user has muted, rather than a boolean
-- column per type: a new notification type is then a code change, not a migration, and the default
-- ('' = nothing muted) preserves the existing behaviour for every current row.
ALTER TABLE user_preferences ADD COLUMN push_disabled_types VARCHAR(255) NOT NULL DEFAULT '';

-- TaskDueSoonJob compared due times in one hard-coded zone (Europe/Istanbul), so an assignee
-- anywhere else got their group-task reminder offset by their own UTC difference. The client now
-- sends its IANA zone with the FCM token; the job resolves the assignee's most recent device.
ALTER TABLE device_tokens ADD COLUMN time_zone VARCHAR(64);
