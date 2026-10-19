-- =============================================
-- Drop the async_task.progress column
--
-- Incremental progress never had a real producer: the one reporter wrote a text message, not a
-- percentage, and the realtime consumer (the SSE task-event bridge into a live chat stream) was
-- removed together with the session-scoped task cards. `status` plus the global Tasks page is the
-- whole surface now, so the column would stay NULL forever.
--
-- V20 is frozen history, so the removal is its own versioned script (same pattern as V17).
-- H2 (MODE=MYSQL) and PostgreSQL both accept DROP COLUMN IF EXISTS.
-- =============================================

ALTER TABLE async_task DROP COLUMN IF EXISTS progress;
