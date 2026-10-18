-- =============================================
-- Per-user scheduled-task opt-in (configuration side of async_task)
--
-- One row per (user, task_type): whether a user takes part in a schedulable task type, when the scan
-- fires for them, and the type-specific configuration. `params` is an opaque JSON blob owned by that
-- type's planner, so teaching the system a new schedulable type adds neither a column nor a table.
-- `schedule_cron` optionally overrides the planner's default scan cron.
--
-- Consolidation is the first and only type today. Its `params` is `{"typeThresholds": {typeDirName:
-- Int}}` — a memory slice is consolidated for a type when its active entry count reaches that number.
-- Enabling is gated at the web boundary on a configured MEMORY_CURATION aux model.
--
-- Ownership is `user_id` strict — no shared `system` fold-in.
--
-- Keep statements restricted to types both H2 (MODE=MYSQL) and PostgreSQL accept,
-- mirroring the verified `async_task` definition in V20.
-- =============================================

CREATE TABLE IF NOT EXISTS scheduled_task_setting (
    user_id VARCHAR(255) NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    enabled BOOLEAN DEFAULT FALSE NOT NULL,
    schedule_cron VARCHAR(64),
    params TEXT,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, task_type)
);

CREATE INDEX IF NOT EXISTS idx_scheduled_task_setting_type_enabled
    ON scheduled_task_setting (task_type, enabled);
