-- =============================================
-- Multiple schedules per (user, task_type)
--
-- A host may run several independent schedules of the same task type — e.g. one per
-- instrument group with its own cron. `schedule_key` distinguishes them; the empty
-- string keeps every legacy row's single-schedule shape and existing readers working.
--
-- Rebuild rather than ALTER DROP CONSTRAINT: H2 (MODE=MYSQL) auto-generates PRIMARY KEY
-- constraint names, so dropping by name is not portable. Keep statements restricted to
-- types both H2 (MODE=MYSQL) and PostgreSQL accept, mirroring V21/V31.
-- =============================================

CREATE TABLE scheduled_task_setting_v2 (
    user_id VARCHAR(255) NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    schedule_key VARCHAR(64) NOT NULL DEFAULT '',
    enabled BOOLEAN DEFAULT FALSE NOT NULL,
    schedule_cron VARCHAR(64),
    params TEXT,
    timeout_ms BIGINT,
    updated_at BIGINT NOT NULL,
    next_fire_at BIGINT,
    schedule_revision VARCHAR(64),
    schedule_zone VARCHAR(64),
    PRIMARY KEY (user_id, task_type, schedule_key)
);

INSERT INTO scheduled_task_setting_v2
    (user_id, task_type, schedule_key, enabled, schedule_cron, params, timeout_ms,
     updated_at, next_fire_at, schedule_revision, schedule_zone)
SELECT user_id, task_type, '', enabled, schedule_cron, params, timeout_ms,
       updated_at, next_fire_at, schedule_revision, schedule_zone
FROM scheduled_task_setting;

DROP TABLE scheduled_task_setting;

ALTER TABLE scheduled_task_setting_v2 RENAME TO scheduled_task_setting;

CREATE INDEX IF NOT EXISTS idx_scheduled_task_setting_type_enabled
    ON scheduled_task_setting (task_type, enabled);

CREATE INDEX IF NOT EXISTS idx_scheduled_task_setting_due
    ON scheduled_task_setting (enabled, task_type, next_fire_at);
