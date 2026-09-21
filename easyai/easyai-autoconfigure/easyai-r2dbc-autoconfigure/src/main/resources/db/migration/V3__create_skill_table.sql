-- =============================================
-- Skill Catalog
--
-- Directory & lifecycle source of truth for skills. SKILL.md content itself stays on
-- disk (install_path); this table records ownership, enablement, provenance and the
-- content checksum used to drive RAG re-indexing. Row-level `user_id` is what lets
-- startup enumerate per-user retrieval slices without a request context.
--
-- Keep statements restricted to types both H2 (MODE=MYSQL) and PostgreSQL accept,
-- mirroring the verified `user_command` definition in V1.
-- =============================================

CREATE TABLE IF NOT EXISTS skill (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
    version VARCHAR(32) NOT NULL DEFAULT '0.0.0',
    checksum VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    install_path VARCHAR(512) NOT NULL,
    origin VARCHAR(512),
    user_id VARCHAR(255) NOT NULL DEFAULT 'system',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_skill_user_id ON skill (user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_skill_user_name ON skill (user_id, name);
