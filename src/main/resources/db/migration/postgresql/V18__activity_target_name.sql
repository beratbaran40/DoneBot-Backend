-- V18: structured target for client-side localization of activity sentences.
-- TASK_ASSIGNED / MEMBER_REMOVED / OWNERSHIP_TRANSFERRED embed the target person's name only
-- inside the pre-rendered English description; clients localizing from (type, taskTitle,
-- targetName) need it as a field. description keeps being written in English for old clients.
ALTER TABLE group_activities ADD COLUMN IF NOT EXISTS target_name VARCHAR(255);
