-- Group invitations: pending accept/decline workflow.

CREATE TABLE group_invitations (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL,
    inviter_user_id BIGINT NOT NULL,
    invitee_user_id BIGINT NOT NULL,
    invitee_email VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    responded_at TIMESTAMPTZ
);

CREATE INDEX idx_invitations_invitee_status ON group_invitations(invitee_user_id, status);
CREATE INDEX idx_invitations_group ON group_invitations(group_id);
