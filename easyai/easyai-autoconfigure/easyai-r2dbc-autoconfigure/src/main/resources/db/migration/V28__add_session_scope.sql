-- =============================================
-- Add session.session_scope
--
-- A host can open sessions that must stay out of Chat History (system_kind) while still being
-- listable by that host for its own feature. The page assistant is the first case: its history
-- is scoped to one page + entity inside the active family, and neither pageId nor entityId fits
-- in the 32-char system_kind marker. This column carries the opaque host-defined scope key.
--
-- system_kind keeps its meaning ("hidden session kind"); Chat History still filters every
-- non-null kind out. The scope is only read by explicit host queries.
-- =============================================

ALTER TABLE session ADD COLUMN IF NOT EXISTS session_scope VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_session_session_scope ON session (session_scope, user_id);
