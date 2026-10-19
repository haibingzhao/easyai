-- =============================================
-- App-scoped asynchronous task table
--
-- Durable record of background work that outlives a chat session. Execution runs on an app-level
-- executor, so `session_id` is only a *reference* (nullable — scheduled work has none) and never a
-- lifecycle owner: closing/aborting the session or restarting the process does not cancel the task.
-- `status` is the single source of truth read back by the global Tasks page and the Session Summary.
-- `payload`/`result` are type-specific JSON; `trigger_source` distinguishes AUTO/FRONTEND/SCHEDULE.
-- `retry_count`/`retried_at` track in-place retries (a retry resets status and clears error/result).
-- Ownership is `user_id` strict — no shared `system` fold-in.
--
-- Keep statements restricted to types both H2 (MODE=MYSQL) and PostgreSQL accept,
-- mirroring the verified `model_catalog` definition in V19.
-- =============================================

CREATE TABLE IF NOT EXISTS async_task (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    trigger_source VARCHAR(32) NOT NULL,
    session_id VARCHAR(255),
    title VARCHAR(255) NOT NULL,
    payload TEXT,
    result TEXT,
    error TEXT,
    progress INTEGER,
    retry_count INTEGER DEFAULT 0 NOT NULL,
    retried_at BIGINT,
    created_at BIGINT NOT NULL,
    started_at BIGINT,
    completed_at BIGINT,
    updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_async_task_user_created ON async_task (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_async_task_user_type_status ON async_task (user_id, type, status);
CREATE INDEX IF NOT EXISTS idx_async_task_session ON async_task (session_id);
