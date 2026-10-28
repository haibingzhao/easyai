CREATE INDEX IF NOT EXISTS idx_async_task_session_status ON async_task (session_id, status);
