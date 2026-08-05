-- V20: platform-level admin role, account status, and the "last seen" timestamp the admin panel reads.
--
-- Until now the only role in the system was per-group (group_members.role); there was no way to mark a
-- platform administrator, and no way to suspend an abusive account short of deleting it. There was also
-- no activity signal at all on users, so DAU/WAU/MAU could not be computed.
--
-- `role` and `status` are safe as column names in both Postgres and H2 (group_members.role already
-- proves it). On PG 11+ `ADD COLUMN ... NOT NULL DEFAULT` is metadata-only, so this neither rewrites the
-- table nor holds a long lock — important on Neon, where a stalled DDL burns compute.
--
-- Being ADMIN is necessary but NOT sufficient to reach /admin/**: the email must also appear in the
-- app.admin.allowed-emails allowlist (env var). Flipping this column alone does not grant access.

ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE users ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE users ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS suspended_reason VARCHAR(500);
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_active_at TIMESTAMP WITH TIME ZONE;

-- status: the admin user list filters on it. created_at: new-signups-per-day charts group by it.
-- last_active_at: the user list sorts by it without a correlated subquery over user_activity_daily.
CREATE INDEX IF NOT EXISTS idx_users_status ON users (status);
CREATE INDEX IF NOT EXISTS idx_users_created_at ON users (created_at);
CREATE INDEX IF NOT EXISTS idx_users_last_active_at ON users (last_active_at);
