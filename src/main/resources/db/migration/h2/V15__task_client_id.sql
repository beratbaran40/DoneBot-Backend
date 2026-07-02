-- §4.12 sync-write idempotency: a client-generated stable key so a retried POST /tasks
-- (lost HTTP response) dedups server-side instead of blind-inserting a duplicate.
-- Nullable: old clients and every pre-existing row keep NULL and fall back to blind insert.
ALTER TABLE tasks ADD COLUMN client_task_id VARCHAR(36);

-- One task per (owner, client key). NULLs are DISTINCT in a unique index on both Postgres and
-- H2, so the many legacy NULL rows never collide with each other.
CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_owner_client ON tasks (owner_id, client_task_id);
