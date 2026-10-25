-- =============================================
-- Create notification
--
-- One row per **recipient**, not per event: read state and which side of the event an account
-- sits on belong to the reader, so a broadcast stays unread for one member after another has
-- opened it. Copies of one business event share `event_id`, which is what a decision resolves on.
--
-- Account-scoped like push_subscription: `user_id` is the login account. `source` separates the
-- publishing product (home, trading) so two applications sharing this framework database do not
-- mix their inboxes.
--
-- Title/body/scope_name/actor_name are text snapshots taken at publish time — the inbox renders
-- without a single business-table join, which is the whole reason one page can serve both products.
--
-- `pending_key` replaces a partial unique index: it carries `scope|source|type` while the row is a
-- *pending incoming* decision and is nulled on every terminal transition, so a plain unique index
-- forbids two concurrent requests for the same object. `CREATE UNIQUE INDEX ... WHERE` would do the
-- same job but H2 rejects it, and these scripts run against H2 file databases too. NULLs never
-- collide in a unique index, which is what keeps resolved rows out of the way.
-- =============================================

CREATE TABLE notification (
    id            VARCHAR(64)   PRIMARY KEY,
    event_id      VARCHAR(64)   NOT NULL,
    user_id       VARCHAR(64)   NOT NULL,
    source        VARCHAR(50)   NOT NULL,
    type          VARCHAR(50)   NOT NULL,
    direction     VARCHAR(12)   NOT NULL,
    title         VARCHAR(200)  NOT NULL,
    body          VARCHAR(1000),
    url           VARCHAR(500),
    scope_type    VARCHAR(50),
    scope_id      VARCHAR(64),
    scope_name    VARCHAR(200),
    actor_user_id VARCHAR(64),
    actor_name    VARCHAR(200),
    target_name   VARCHAR(200),
    action_status VARCHAR(20)   NOT NULL,
    action_at     BIGINT,
    action_by     VARCHAR(64),
    read_at       BIGINT,
    created_at    BIGINT        NOT NULL,
    expires_at    BIGINT,
    dedupe_key    VARCHAR(128),
    pending_key   VARCHAR(300),
    payload       TEXT,
    CONSTRAINT ck_notification_direction CHECK (direction IN ('INCOMING', 'OUTGOING', 'INFO')),
    CONSTRAINT ck_notification_action CHECK (
        action_status IN ('NONE', 'PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'EXPIRED')
    )
);

CREATE UNIQUE INDEX uk_notification_dedupe ON notification (user_id, source, dedupe_key);
CREATE UNIQUE INDEX uk_notification_pending ON notification (pending_key);
CREATE INDEX idx_notification_inbox ON notification (user_id, source, created_at DESC);
CREATE INDEX idx_notification_event ON notification (event_id);
