-- Session variables migrated to compaction summary message metadata (Message.metadata -> sessionVariables).
-- The dedicated column on the session table is no longer used.
ALTER TABLE session DROP COLUMN IF EXISTS variables_json;
