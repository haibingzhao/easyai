-- =============================================
-- Knowledge article draft/published lifecycle
--
-- `status` is the boundary between a private draft and retrievable knowledge: only 'published'
-- rows are indexed into EasyRAG (and thus visible to the knowledge_* tools). Drafts are visible
-- to their creator alone.
--
-- Existing rows default to 'published', which matches their pre-migration behaviour: they were
-- listed as articles and (for file uploads) indexed.
-- =============================================

ALTER TABLE knowledge_article ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'published';
CREATE INDEX IF NOT EXISTS idx_ka_user_status ON knowledge_article (user_id, status);
