-- V11: staged tasks. A "staged" task owns an ordered list of steps (subtasks).
-- Until now steps lived only in the Android client's Room DB (device-local), so the
-- DoneBot chatbot could neither create nor edit them and they never survived a reinstall
-- or reached a second device. This table promotes steps to a synced, server-owned
-- resource so chat tools (createStagedTask/addStep/...) and cross-device sync work.
--
-- ON DELETE CASCADE mirrors the client's Room FK: deleting the parent task removes its
-- steps. A staged task always has >=1 step (enforced by the client + chat tools).

CREATE TABLE task_subtasks (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    is_completed BOOLEAN NOT NULL DEFAULT FALSE,
    order_index INT NOT NULL DEFAULT 0
);

CREATE INDEX ix_task_subtasks_task ON task_subtasks (task_id);
