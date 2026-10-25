-- =============================================
-- Per-task timeout
--
-- A task row records the wall-clock bound its runner enforces, and the settings row it was minted
-- from records the per-user override. Null keeps the old unbounded behavior (memory consolidation
-- today); a host that wants a bound sets it on its scheduled setting or on a manually submitted task.
--
-- Both columns are nullable and additive. Keep statements restricted to types both H2 (MODE=MYSQL)
-- and PostgreSQL accept, mirroring V21/V22.
-- =============================================

ALTER TABLE async_task ADD COLUMN IF NOT EXISTS timeout_ms BIGINT;
ALTER TABLE scheduled_task_setting ADD COLUMN IF NOT EXISTS timeout_ms BIGINT;
