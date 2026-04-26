-- V2: split recurrence from category. Add BIRTHDAY category, add Recurrence enum.
-- Existing rows where the V1 implicit-DAILY-category was set get migrated to
-- (category=PERSONAL, recurrence=DAILY) — the new orthogonal model.

ALTER TABLE tasks ADD COLUMN recurrence VARCHAR(16) NOT NULL DEFAULT 'NONE';

UPDATE tasks
SET recurrence = 'DAILY',
    category = 'PERSONAL'
WHERE category = 'DAILY';
