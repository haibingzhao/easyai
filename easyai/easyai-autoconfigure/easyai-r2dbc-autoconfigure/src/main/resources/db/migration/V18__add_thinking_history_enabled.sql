-- Agent-level gate for replaying persisted thinking blocks as assistant history.
ALTER TABLE agent ADD COLUMN IF NOT EXISTS thinking_history_enabled BOOLEAN NOT NULL DEFAULT FALSE;
