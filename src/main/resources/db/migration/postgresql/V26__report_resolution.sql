-- V26: give the two moderation tables a resolution state.
--
-- chat_reports (V13) and content_reports (V16) have been write-only since the day they shipped: their
-- repositories expose nothing but existsBy…Hash, so reports go in and no code ever reads them back. The
-- entire moderation workflow today is a best-effort email to the admin inbox. Under Google Play's
-- user-generated-content policy the requirement is not just that users can report, but that reports are
-- acted on — and right now there is no way to even see the queue, let alone show it was worked.
--
-- Defaulting existing rows to OPEN is correct rather than convenient: nothing has been reviewed, so
-- every historical report genuinely is outstanding and should surface in the queue on day one.
--
-- resolved_by carries no FK for the same reason as admin_audit_log: the decision must remain
-- attributable after the deciding account is gone.

ALTER TABLE chat_reports ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'OPEN';
ALTER TABLE chat_reports ADD COLUMN IF NOT EXISTS resolution VARCHAR(24);
ALTER TABLE chat_reports ADD COLUMN IF NOT EXISTS resolution_note VARCHAR(500);
ALTER TABLE chat_reports ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE chat_reports ADD COLUMN IF NOT EXISTS resolved_by BIGINT;

ALTER TABLE content_reports ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'OPEN';
ALTER TABLE content_reports ADD COLUMN IF NOT EXISTS resolution VARCHAR(24);
ALTER TABLE content_reports ADD COLUMN IF NOT EXISTS resolution_note VARCHAR(500);
ALTER TABLE content_reports ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE content_reports ADD COLUMN IF NOT EXISTS resolved_by BIGINT;

-- The queue is always read as "open reports, oldest first", so status leads the index.
CREATE INDEX IF NOT EXISTS idx_chat_reports_status ON chat_reports (status, created_at);
CREATE INDEX IF NOT EXISTS idx_content_reports_status ON content_reports (status, created_at);
