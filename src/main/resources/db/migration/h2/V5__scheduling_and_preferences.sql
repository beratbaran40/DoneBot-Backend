-- Phase 3: due-soon dedup column on tasks.
ALTER TABLE tasks ADD COLUMN due_soon_notified_at TIMESTAMP WITH TIME ZONE;

-- Phase 4: user push preferences (master toggle).
CREATE TABLE user_preferences (
    user_id BIGINT PRIMARY KEY,
    push_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
