-- V22: one row per user per day they were active. This is the table every engagement number in the
-- admin panel is derived from — DAU, WAU, MAU, retention cohorts and the per-user activity sparkline.
--
-- Why a table rather than just users.last_active_at: a single timestamp is overwritten on every visit,
-- so it can answer "when were they last seen" but never "how many distinct people used the app on the
-- 14th". Keeping the day rows makes history queryable and lets cohort retention be computed exactly
-- instead of estimated.
--
-- Writes are cheap by construction: the recorder de-duplicates in memory, so this table takes at most
-- one insert per user per day no matter how many requests they make.
--
-- `source` distinguishes rows reconstructed from historical evidence (V23 backfill) from rows observed
-- live. The two mean subtly different things — a backfilled day proves the user *wrote* something,
-- a live day only proves they made a request — and charting them as one series produces a fake
-- step-change on the cutover day that reads like a growth event.

CREATE TABLE IF NOT EXISTS user_activity_daily (
    user_id       BIGINT     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    activity_date DATE       NOT NULL,
    source        VARCHAR(8) NOT NULL DEFAULT 'live',
    PRIMARY KEY (user_id, activity_date)
);

-- DAU/WAU/MAU scan by date across all users; the primary key is user-first so it cannot serve them.
CREATE INDEX IF NOT EXISTS idx_user_activity_date ON user_activity_daily (activity_date);
