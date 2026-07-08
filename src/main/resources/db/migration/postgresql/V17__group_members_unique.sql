-- V17: enforce one membership row per (group_id, user_id).
-- V1 declared idx_group_members_unique, but prod Postgres pre-dates Flyway (baseline-on-migrate
-- skipped V1__baseline.sql) and ddl-auto=validate does not verify indexes, so the unique index
-- may be missing there. Duplicate membership rows mirror 1:1 into GET /family-groups as
-- duplicate group cards on the client. Dedup first (keep the earliest row per pair), then
-- (re)create the index idempotently.
DELETE FROM group_members
WHERE id NOT IN (SELECT MIN(id) FROM group_members GROUP BY group_id, user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_group_members_unique ON group_members (group_id, user_id);
