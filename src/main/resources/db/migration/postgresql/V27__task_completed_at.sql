-- V27: when a task was completed.
--
-- Until now `tasks` records only a boolean. `finished_on` marks the day a *routine* was retired, and
-- task_daily_completions covers recurring tasks only — so for ordinary one-off tasks, which are most of
-- them, there is no record of *when* completion happened. That makes "how many tasks were completed
-- yesterday" unanswerable, which for a to-do app is the single most important product metric.
--
-- Existing completed rows stay NULL rather than being backfilled from created_at. Inventing a
-- completion time from a creation time would look like data and read like fact while being fiction; a
-- completion-rate chart that honestly starts on deploy day is worth more than one that starts earlier
-- and lies. AdminOverview therefore returns null for completedToday until this column has real data.

ALTER TABLE tasks ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP WITH TIME ZONE;

-- Partial-index territory in Postgres, but a plain index keeps the two vendor copies identical and the
-- table is small; range scans by completion day are what the overview and time series both need.
CREATE INDEX IF NOT EXISTS idx_tasks_completed_at ON tasks (completed_at);
