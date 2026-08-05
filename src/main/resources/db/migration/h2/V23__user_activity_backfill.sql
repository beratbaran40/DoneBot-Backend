-- V23 (H2 variant) — see the postgresql/ copy for the full rationale. This is the ONE migration in the
-- project whose two vendor copies genuinely differ; every other pair is byte-identical.
--
-- Two dialect differences, both easy to get wrong:
--
-- 1. Epoch-day arithmetic. Postgres adds an integer to a DATE directly (`DATE '1970-01-01' + n`); H2
--    has no such operator and needs DATEADD, whose result is a TIMESTAMP and so must be cast back.
--
-- 2. Identifier case. `group_activities.timestamp` was created unquoted in V1, and unquoted identifiers
--    fold to lowercase in Postgres but UPPERCASE in H2. Quoting is unavoidable — bare `timestamp` is a
--    type keyword — so the quoted spelling has to differ per vendor: "timestamp" there, "TIMESTAMP"
--    here. A copy-pasted lowercase quote would fail to resolve on H2 and break every test's context.

INSERT INTO user_activity_daily (user_id, activity_date, source)
SELECT DISTINCT src.uid, src.d, 'backfill'
FROM (
    SELECT rt.user_id       AS uid, CAST(rt.created_at AS DATE)                                  AS d FROM refresh_tokens rt
    UNION
    SELECT t.owner_id       AS uid, CAST(t.created_at AS DATE)                                   AS d FROM tasks t
    UNION
    SELECT tdc.user_id      AS uid, CAST(DATEADD('DAY', tdc.date, DATE '1970-01-01') AS DATE)    AS d FROM task_daily_completions tdc
    UNION
    SELECT ga.actor_user_id AS uid, CAST(ga."TIMESTAMP" AS DATE)                                 AS d FROM group_activities ga
) src
WHERE src.uid IN (SELECT id FROM users)
  AND src.d >= DATE '2025-01-01'
  AND src.d <= CURRENT_DATE
  AND NOT EXISTS (
      SELECT 1 FROM user_activity_daily existing
      WHERE existing.user_id = src.uid AND existing.activity_date = src.d
  );
