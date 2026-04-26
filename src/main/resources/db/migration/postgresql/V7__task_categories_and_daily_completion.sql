-- Task categories: organize personal tasks (Daily, Shopping, Medicine, etc.)
ALTER TABLE tasks ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'PERSONAL';
ALTER TABLE tasks ADD COLUMN custom_category_name VARCHAR(64);

-- Per-day completion for DAILY-category tasks (so completing on Tuesday
-- doesn't carry to Wednesday and stays in sync across devices).
CREATE TABLE task_daily_completions (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    date BIGINT NOT NULL,
    completed_at BIGINT NOT NULL,
    CONSTRAINT uk_task_daily_completion UNIQUE (task_id, date)
);

CREATE INDEX ix_task_daily_completion_user_date ON task_daily_completions (user_id, date);
