-- =============================================
-- Skill Catalog: project-scoped identity
--
-- One row used to be addressable by (user_id, name) alone, so two projects' same-named skills
-- collapsed into one install_path. This adds a granularity key derived from the install path:
-- `project_hash` is the leading SHA-256 hex of the normalized project path ('' = GLOBAL). It is
-- purely an identity token — the path itself is never read back from it; SkillScopeResolver keeps
-- deriving scope from install_path, so there is no second source of truth.
--
-- The unique index therefore becomes (user_id, name, project_hash). install_path stays out of the
-- index on purpose: VARCHAR(512) would blow past MySQL InnoDB's 3072-byte key limit.
--
-- Keep statements restricted to types both H2 (MODE=MYSQL) and PostgreSQL accept.
-- =============================================

ALTER TABLE skill ADD COLUMN IF NOT EXISTS project_hash VARCHAR(16) NOT NULL DEFAULT '';

-- Pre-release: no rows worth migrating
DELETE FROM skill;

DROP INDEX IF EXISTS uq_skill_user_name;
CREATE UNIQUE INDEX IF NOT EXISTS uq_skill_user_name_hash ON skill (user_id, name, project_hash);
