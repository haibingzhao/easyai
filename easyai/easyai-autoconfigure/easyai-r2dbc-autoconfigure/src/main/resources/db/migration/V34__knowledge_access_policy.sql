-- =============================================
-- Knowledge article access policy
--
-- Product-level ACLs move out of the framework: per-article grants and visibility are
-- replaced by a host-provided KnowledgeAccessPolicy.
--
-- DATA IS NOT MIGRATED. knowledge_article_access grants and knowledge_article.visibility
-- are dropped without a backfill: the creator identity behind a group-bucket row was never
-- stored, so there is nothing reliable to copy into created_by. Existing rows stay NULL and
-- fall back to owner-bucket semantics (the default policy; a host policy may map them
-- differently, e.g. to a shared grant). Editing a legacy row does not stamp created_by, so
-- it stays owner-less until a host assigns one explicitly.
--
-- RELEASE ORDER: the host policy bean ships with this framework upgrade. A host that turns
-- on easyai.knowledge.shared-within-group without providing a policy gets the default
-- creator-only access for new rows, not the old group-wide read/write.
-- =============================================

DROP TABLE IF EXISTS knowledge_article_access;
DROP INDEX IF EXISTS idx_ka_visibility;
ALTER TABLE knowledge_article DROP COLUMN IF EXISTS visibility;
ALTER TABLE knowledge_article ADD COLUMN IF NOT EXISTS created_by VARCHAR(255);
