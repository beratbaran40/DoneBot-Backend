-- V17: enforce one membership row per (group_id, user_id).
-- On H2 (dev/test) V1__baseline.sql already created idx_group_members_unique, so both
-- statements are no-ops here; the real target is prod Postgres, whose pre-Flyway schema
-- (baseline-on-migrate skipped V1) may lack the unique index. Kept identical to the
-- postgresql variant. Dedup first, then (re)create the index idempotently.
DELETE FROM group_members
WHERE id NOT IN (SELECT MIN(id) FROM group_members GROUP BY group_id, user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_group_members_unique ON group_members (group_id, user_id);
