ALTER TABLE session ADD COLUMN IF NOT EXISTS forked_from_session_id VARCHAR(255);
ALTER TABLE session ADD COLUMN IF NOT EXISTS forked_from_message_id VARCHAR(255);
ALTER TABLE session ADD COLUMN IF NOT EXISTS fork_root_session_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_session_fork_root ON session (fork_root_session_id);
CREATE INDEX IF NOT EXISTS idx_session_forked_from ON session (forked_from_session_id);
