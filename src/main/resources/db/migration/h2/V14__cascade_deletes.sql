-- Mirror of postgresql/V14__cascade_deletes.sql (kept in sync). H2 dev/test DB.
-- Cascade-delete user/group children so account/group/task deletion leaves no orphan rows (§4.20) and
-- fix the task_photos bytea-blob leak (§4.19). Orphans are purged first so each ADD CONSTRAINT validates.

-- refresh_tokens -> users
DELETE FROM refresh_tokens WHERE user_id NOT IN (SELECT id FROM users);
ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_tokens_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- device_tokens -> users
DELETE FROM device_tokens WHERE user_id NOT IN (SELECT id FROM users);
ALTER TABLE device_tokens ADD CONSTRAINT fk_device_tokens_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- notifications -> users
DELETE FROM notifications WHERE user_id NOT IN (SELECT id FROM users);
ALTER TABLE notifications ADD CONSTRAINT fk_notifications_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- password_reset_tokens -> users
DELETE FROM password_reset_tokens WHERE user_id NOT IN (SELECT id FROM users);
ALTER TABLE password_reset_tokens ADD CONSTRAINT fk_password_reset_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- chat_reports -> users
DELETE FROM chat_reports WHERE user_id NOT IN (SELECT id FROM users);
ALTER TABLE chat_reports ADD CONSTRAINT fk_chat_reports_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- user_preferences -> users (user_id is the PK)
DELETE FROM user_preferences WHERE user_id NOT IN (SELECT id FROM users);
ALTER TABLE user_preferences ADD CONSTRAINT fk_user_preferences_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- group_members -> family_groups, users
DELETE FROM group_members WHERE group_id NOT IN (SELECT id FROM family_groups);
DELETE FROM group_members WHERE user_id NOT IN (SELECT id FROM users);
ALTER TABLE group_members ADD CONSTRAINT fk_group_members_group
    FOREIGN KEY (group_id) REFERENCES family_groups (id) ON DELETE CASCADE;
ALTER TABLE group_members ADD CONSTRAINT fk_group_members_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- group_activities -> family_groups, users
DELETE FROM group_activities WHERE group_id NOT IN (SELECT id FROM family_groups);
DELETE FROM group_activities WHERE actor_user_id NOT IN (SELECT id FROM users);
ALTER TABLE group_activities ADD CONSTRAINT fk_group_activities_group
    FOREIGN KEY (group_id) REFERENCES family_groups (id) ON DELETE CASCADE;
ALTER TABLE group_activities ADD CONSTRAINT fk_group_activities_actor
    FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE CASCADE;

-- group_invitations -> family_groups, users (invite always targets a registered user, so both refs exist)
DELETE FROM group_invitations WHERE group_id NOT IN (SELECT id FROM family_groups);
DELETE FROM group_invitations WHERE inviter_user_id NOT IN (SELECT id FROM users);
DELETE FROM group_invitations WHERE invitee_user_id NOT IN (SELECT id FROM users);
ALTER TABLE group_invitations ADD CONSTRAINT fk_group_invitations_group
    FOREIGN KEY (group_id) REFERENCES family_groups (id) ON DELETE CASCADE;
ALTER TABLE group_invitations ADD CONSTRAINT fk_group_invitations_inviter
    FOREIGN KEY (inviter_user_id) REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE group_invitations ADD CONSTRAINT fk_group_invitations_invitee
    FOREIGN KEY (invitee_user_id) REFERENCES users (id) ON DELETE CASCADE;

-- tasks: group tasks die with their group (CASCADE); a deleted assignee is unassigned (SET NULL);
-- owner_id intentionally left without a FK (personal-task deletion stays in AccountDeletionService).
DELETE FROM tasks WHERE family_group_id IS NOT NULL AND family_group_id NOT IN (SELECT id FROM family_groups);
UPDATE tasks SET assigned_to_user_id = NULL
    WHERE assigned_to_user_id IS NOT NULL AND assigned_to_user_id NOT IN (SELECT id FROM users);
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_group
    FOREIGN KEY (family_group_id) REFERENCES family_groups (id) ON DELETE CASCADE;
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_assignee
    FOREIGN KEY (assigned_to_user_id) REFERENCES users (id) ON DELETE SET NULL;

-- task_photos -> tasks (kills the bytea-blob leak on every task/group/account delete path, §4.19)
DELETE FROM task_photos WHERE task_id NOT IN (SELECT id FROM tasks);
ALTER TABLE task_photos ADD CONSTRAINT fk_task_photos_task
    FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE;
