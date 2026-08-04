-- V19: the recurrence rule grows beyond a bare enum, for the client's "custom" task type.
--
-- Until now a routine could only be "every DAILY/WEEKLY/MONTHLY/YEARLY, forever, with one reminder".
-- That cannot express the cases the custom type is for: "every other day", "Mon/Wed/Fri", "for one
-- month", "remind me at 08:00, 14:00 and 20:00". The Android client models all four; these columns
-- let them survive a reinstall and reach a second device.
--
-- Every column is nullable or defaulted so existing rows are untouched and keep behaving identically:
-- recurrence_interval = 1 + recurrence_by_day NULL + recurrence_until NULL IS the legacy rule.
--
-- recurrence_until is deliberately SEPARATE from finished_on. This is the end the user SCHEDULED when
-- creating the task; finished_on is the manual "I'm done with this routine" retire. Both cut the rule
-- off and the earlier one wins — collapsing them would make "retired early" indistinguishable from
-- "was always meant to be shorter", and the client shows different UI for each.
ALTER TABLE tasks ADD COLUMN recurrence_interval INT NOT NULL DEFAULT 1;
ALTER TABLE tasks ADD COLUMN recurrence_by_day VARCHAR(64);
ALTER TABLE tasks ADD COLUMN recurrence_until BIGINT;

-- Absolute reminder times of day, CSV of SECOND-of-day to match tasks.time_start
-- ("28800,50400,72000" = 08:00 / 14:00 / 20:00). NOTE the client stores MINUTE-of-day in Room and
-- converts at its mapper boundary, exactly like time_start/time_end — mixing the two is a 60x error.
--
-- A column and not a child table because the server never schedules anything: personal-task reminders
-- are client-local exact alarms (see TaskDueSoonJob), so this is pure sync payload with no query needs.
-- reminder_offset_minutes stays populated with the primary reminder so older clients still get one.
ALTER TABLE tasks ADD COLUMN reminder_times VARCHAR(128);
