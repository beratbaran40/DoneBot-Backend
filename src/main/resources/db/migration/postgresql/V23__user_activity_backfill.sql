-- V23: reconstruct activity history from evidence already in the database, so the panel opens with
-- real charts going back to launch instead of starting from zero on deploy day.
--
-- Four independent traces of "this user did something on this day":
--   refresh_tokens.created_at        — the app rotated a token, i.e. it was opened
--   tasks.created_at                 — a task was created
--   task_daily_completions.date      — a routine was ticked off (epoch DAY, not a timestamp)
--   group_activities.timestamp       — the user acted inside a group
--
-- Three things here are load-bearing; each one breaks a production deploy if dropped.
--
-- 1. `src.uid IN (SELECT id FROM users)`. V14 states outright: "Deliberately NO FK on tasks.owner_id or
--    family_groups.owner_id" — account deletion transfers owned rows rather than cascading them. So
--    orphan owner_id values pointing at deleted users really do exist in production, and inserting one
--    into a table whose user_id DOES have a foreign key aborts the whole migration.
--
-- 2. `NOT EXISTS (...)`. V22 ships and starts recording live rows before this migration runs, so by the
--    time the backfill executes, today (and any day since) is already present. Without this guard the
--    insert hits the primary key and the deploy fails.
--
-- 3. The lower date bound. task_daily_completions.date is an epoch day, so a zero or corrupt value
--    would silently land on 1970-01-01 and stretch every chart's x-axis by half a century.
--
-- `source = 'backfill'` marks these rows as weaker evidence than live ones: a backfilled day proves the
-- user *wrote* something, a live day only proves they made a request. Charting both as one series puts
-- a step-change on the cutover day that reads like sudden growth.

INSERT INTO user_activity_daily (user_id, activity_date, source)
SELECT DISTINCT src.uid, src.d, 'backfill'
FROM (
    SELECT rt.user_id      AS uid, CAST(rt.created_at AS DATE)                  AS d FROM refresh_tokens rt
    UNION
    SELECT t.owner_id      AS uid, CAST(t.created_at AS DATE)                   AS d FROM tasks t
    UNION
    SELECT tdc.user_id     AS uid, DATE '1970-01-01' + CAST(tdc.date AS INTEGER) AS d FROM task_daily_completions tdc
    UNION
    SELECT ga.actor_user_id AS uid, CAST(ga."timestamp" AS DATE)                AS d FROM group_activities ga
) src
WHERE src.uid IN (SELECT id FROM users)
  AND src.d >= DATE '2025-01-01'
  AND src.d <= CURRENT_DATE
  AND NOT EXISTS (
      SELECT 1 FROM user_activity_daily existing
      WHERE existing.user_id = src.uid AND existing.activity_date = src.d
  );
