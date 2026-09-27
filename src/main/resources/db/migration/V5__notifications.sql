-- Per-recipient activity feed (fan-out on write).
CREATE TABLE notifications (
    id                UUID PRIMARY KEY,
    recipient_user_id BIGINT       NOT NULL,
    type              VARCHAR(32)  NOT NULL,
    title             VARCHAR(200) NOT NULL,
    body              VARCHAR(500),
    project_id        UUID,
    actor_user_id     BIGINT,
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_notif_recipient_created ON notifications (recipient_user_id, created_at);
CREATE INDEX ix_notif_recipient_unread ON notifications (recipient_user_id, read_at);
