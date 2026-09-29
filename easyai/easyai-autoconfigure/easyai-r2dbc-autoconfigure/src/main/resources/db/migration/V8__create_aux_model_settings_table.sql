-- =============================================
-- Per-user auxiliary model settings
--
-- One row per (user, task): a task-keyed choice of which saved model_provider_config backs a
-- background purpose (e.g. context compaction). A blank model_config_id means the task is
-- unconfigured, so its consumer falls back to the default (for compaction, the chat-session model).
-- Edited from the frontend Settings page and applied without a restart.
--
-- Keep statements restricted to types both H2 (MODE=MYSQL) and PostgreSQL accept,
-- mirroring the verified `storage_settings` definition in V4.
-- =============================================

CREATE TABLE IF NOT EXISTS aux_model_settings (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL DEFAULT 'system',
    task_key VARCHAR(64) NOT NULL,
    model_config_id VARCHAR(255) NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_aux_model_user_task ON aux_model_settings (user_id, task_key);
