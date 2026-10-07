-- Temporary workspace projects (kind='temp') are system-managed scratch directories,
-- created lazily for sessions that are not bound to a user-picked project.
ALTER TABLE project ADD COLUMN IF NOT EXISTS kind VARCHAR(32) NOT NULL DEFAULT 'user';
