-- V9: promote `is_all_day` and `reminder_offset_minutes` from device-local to wire fields.
-- The Android client already had these in Room since the original schema, but the backend
-- never carried them. The DoneBot chatbot needs both so all-day tasks and reminder hints
-- ("remind me 30 min before") set from chat survive cross-device sync and reinstalls.

ALTER TABLE tasks ADD COLUMN is_all_day BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tasks ADD COLUMN reminder_offset_minutes BIGINT NOT NULL DEFAULT 0;
