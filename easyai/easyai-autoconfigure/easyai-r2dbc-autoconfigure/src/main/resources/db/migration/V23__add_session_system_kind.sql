-- =============================================
-- Add session.system_kind and backfill task sessions
--
-- Chat History excluded a session from the async-task run list by asking whether an `async_task`
-- row still referenced it. That is a reverse-existence test, not a property of the session: deleting
-- the task (the Tasks page trash button, or "Clear completed") made every hidden consolidation
-- session reappear as if it were an ordinary chat. The marker belongs on the session row itself.
--
-- Backfill in the same script so sessions written before this column exist keep their old behaviour.
-- H2 (MODE=MYSQL) and PostgreSQL both accept ADD COLUMN IF NOT EXISTS and this UPDATE form.
-- =============================================

ALTER TABLE session ADD COLUMN IF NOT EXISTS system_kind VARCHAR(32);

UPDATE session
SET system_kind = 'async_task'
WHERE system_kind IS NULL
  AND id IN (SELECT session_id FROM async_task WHERE session_id IS NOT NULL);
