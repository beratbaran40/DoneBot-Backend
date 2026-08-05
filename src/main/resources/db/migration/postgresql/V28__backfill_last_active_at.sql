-- V28: give users.last_active_at the history that V23 reconstructed.
--
-- V23 rebuilt activity history into user_activity_daily but left users.last_active_at alone, because
-- only the live recorder writes it. The result is a column that is NULL for everyone who has not opened
-- the app since the deploy — so the admin user list shows "Never" beside accounts whose activity the
-- panel demonstrably knows about, and sorting by "recently active" is useless until the whole user base
-- happens to come back.
--
-- Only fills rows that are still NULL, so a value already written by the live recorder — which is more
-- precise than a date — is never overwritten by a coarser one.
--
-- The date becomes midnight UTC of that day — an honest approximation, since the source is a day
-- marker and no finer truth exists to recover.
--
-- AT TIME ZONE 'UTC' rather than a bare cast: in Postgres it reads the date as being in UTC and pins
-- the result there, instead of resolving against whatever timezone the session happens to carry. The
-- panel formats every timestamp in UTC, so a value anchored to a +03 session would render as the
-- previous day.
--
-- H2 reads the same expression as a conversion *from* the session zone rather than an interpretation,
-- so a developer machine outside UTC will see these dates a day early locally. Production runs
-- Postgres in UTC, where both readings agree; the difference is a dev-only display artefact, not
-- something the stored data depends on.

UPDATE users u
SET last_active_at = (
    SELECT CAST(MAX(a.activity_date) AS TIMESTAMP) AT TIME ZONE 'UTC'
    FROM user_activity_daily a
    WHERE a.user_id = u.id
)
WHERE u.last_active_at IS NULL
  AND EXISTS (SELECT 1 FROM user_activity_daily a WHERE a.user_id = u.id);
