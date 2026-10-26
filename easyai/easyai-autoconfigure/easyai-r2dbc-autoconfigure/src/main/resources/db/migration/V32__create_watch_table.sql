-- =============================================
-- Create watch
--
-- A watch is a *definition* of a standing intent created from a chat; each firing is an ordinary
-- `async_task` row (type=watch), so this table never holds run state beyond a small last-run cache.
--
-- `next_due_at` is the scheduler's due key and is advanced by a guarded UPDATE before a task is
-- enqueued (`WHERE next_due_at = <the value read>`), so two ticks cannot fire the same watch twice.
-- `pending_action=EXPIRY_REVIEW` marks a lifetime that ran out and is waiting for the model's
-- renew/complete/expire decision; the row stays ACTIVE so its own guard time wins over a normal
-- check. `version` is bumped by every edit and is the guard an in-flight run checks before applying
-- a finding — a user edit always wins over a result it did not see.
--
-- Timestamps are epoch millis. JSON text columns: tags_json (the tag universe), notify_tags_json,
-- owners_json (ordered read-visibility set, self -> group -> system), allowed_tools_json (the
-- approved read-only tool names) and last_finding_json (the newest conclusion, denormalized so the
-- list page needs one query). Ownership is `user_id` strict.
-- =============================================

CREATE TABLE watch (
    id                    VARCHAR(255) PRIMARY KEY,
    user_id               VARCHAR(255) NOT NULL,
    title                 VARCHAR(200) NOT NULL,
    condition_text        TEXT         NOT NULL,
    mode                  VARCHAR(16)  NOT NULL,
    reminder_text         TEXT,
    schedule_kind         VARCHAR(16)  NOT NULL,
    timezone              VARCHAR(64)  NOT NULL,
    local_time            VARCHAR(8),
    weekday               VARCHAR(16),
    day_of_month          INT,
    anchor_at             BIGINT,
    until_at              BIGINT,
    next_due_at           BIGINT       NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    pending_action        VARCHAR(16)  NOT NULL,
    version               INT          NOT NULL,
    extension_count       INT          NOT NULL DEFAULT 0,
    tags_json             TEXT         NOT NULL,
    complete_on_met       BOOLEAN      NOT NULL DEFAULT TRUE,
    notify_policy         VARCHAR(16)  NOT NULL,
    notify_tags_json      TEXT,
    source_agent_id       VARCHAR(128) NOT NULL,
    model_config_id       VARCHAR(255),
    project_id            VARCHAR(255),
    project_path          TEXT,
    project_kind          VARCHAR(16)  NOT NULL,
    owners_json           TEXT         NOT NULL,
    origin_session_id     VARCHAR(255),
    run_session_id        VARCHAR(255) NOT NULL,
    allowed_tools_json    TEXT         NOT NULL,
    source_hints          TEXT,
    last_task_id          VARCHAR(255),
    last_run_at           BIGINT,
    last_finding_json     TEXT,
    consecutive_failures  INT          NOT NULL DEFAULT 0,
    last_error            TEXT,
    status_reason         TEXT,
    created_at            BIGINT       NOT NULL,
    updated_at            BIGINT       NOT NULL,
    CONSTRAINT ck_watch_mode CHECK (mode IN ('CHECK', 'REMIND')),
    CONSTRAINT ck_watch_schedule CHECK (schedule_kind IN ('AT', 'DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_watch_status CHECK (
        status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'EXPIRED', 'CANCELLED')
    ),
    CONSTRAINT ck_watch_pending CHECK (pending_action IN ('CHECK', 'EXPIRY_REVIEW'))
);

CREATE INDEX idx_watch_due ON watch (user_id, status, next_due_at);
CREATE INDEX idx_watch_created ON watch (user_id, created_at DESC);
CREATE INDEX idx_watch_session ON watch (run_session_id);
