-- In-app alerts marked as read (FR-5.1, PLAIN.md 10.2 notifications.read_at). Alerts are counted live (AlertService);
-- a member marks a kind read at its count then. It shows as unread again when the count grows (something new), and
-- disappears by itself once the count is zero (the problem was fixed).
CREATE TABLE alert_reads (
    business_id BIGINT      NOT NULL REFERENCES businesses (id),
    user_id     BIGINT      NOT NULL REFERENCES users (id),
    kind        VARCHAR(40) NOT NULL,
    read_count  BIGINT      NOT NULL,
    read_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (business_id, user_id, kind)
);
