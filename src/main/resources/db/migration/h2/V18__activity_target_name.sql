-- V18: structured target for client-side localization of activity sentences.
-- Identical to the postgresql variant; see that file for the rationale.
ALTER TABLE group_activities ADD COLUMN IF NOT EXISTS target_name VARCHAR(255);
