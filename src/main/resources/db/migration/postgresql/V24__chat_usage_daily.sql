-- V24: durable per-user, per-day DoneBot usage.
--
-- ChatUsageTracker keeps these counters in a ConcurrentHashMap, so every Render deploy — several a week
-- — resets them to zero. That makes the single most expensive part of the product the one part with no
-- history: "how much did chat cost last month" is currently unanswerable except by grepping logs that
-- have already rolled over.
--
-- Tokens, not requests, are what Vertex bills for. A refused turn and a five-tool-call turn are the
-- same request and wildly different money, so prompt/response tokens are stored separately from the
-- request count rather than inferred from it.
--
-- `errors` exists because error rate is currently unmeasurable: ChatService only records on its success
-- and safety-refusal paths, so every quota rejection, Vertex outage and turn-deadline abort is
-- invisible. ChatUsageRecorder records on all of them.
--
-- Bucketed by UTC date to match ChatUsageTracker.tryAcquireGlobalDaily, which already rolls its global
-- budget at UTC midnight. Any other choice makes "N of the 5000 daily budget used" a lie for three
-- hours a day in Istanbul.

CREATE TABLE IF NOT EXISTS chat_usage_daily (
    usage_date      DATE   NOT NULL,
    user_id         BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    requests        INT    NOT NULL DEFAULT 0,
    refusals        INT    NOT NULL DEFAULT 0,
    errors          INT    NOT NULL DEFAULT 0,
    prompt_tokens   BIGINT NOT NULL DEFAULT 0,
    response_tokens BIGINT NOT NULL DEFAULT 0,
    total_server_ms BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (usage_date, user_id)
);

-- Per-user drill-down on the admin user detail screen; the primary key is date-first and cannot serve it.
CREATE INDEX IF NOT EXISTS idx_chat_usage_user ON chat_usage_daily (user_id, usage_date);
