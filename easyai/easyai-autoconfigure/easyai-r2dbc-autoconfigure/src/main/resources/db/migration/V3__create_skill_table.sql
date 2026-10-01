-- =============================================
-- Skill Catalog
--
-- Directory & lifecycle source of truth for skills, owned per user (user_id="system" is the
-- shared read-only layer). Skill content lives in two places: the working copy under the
-- owner's root (install_path) and the authoritative zip package in object storage (object_key).
-- This table records ownership, enablement and the whole-directory checksum used to detect drift
-- and drive RAG re-indexing. Row-level `user_id` lets startup enumerate per-user sync/slice work
-- without a request context.
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
    root_path VARCHAR(512) NOT NULL,
    install_path VARCHAR(512) NOT NULL,
    object_key VARCHAR(512) NOT NULL DEFAULT '',
    user_id VARCHAR(255) NOT NULL DEFAULT 'system',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    indexed_checksum VARCHAR(64),
    sync_state VARCHAR(24) NOT NULL DEFAULT 'PENDING_INDEX',
    revision BIGINT NOT NULL DEFAULT 0,
    next_attempt_at BIGINT,
    last_error VARCHAR(2000)
);

CREATE INDEX IF NOT EXISTS idx_skill_user_id ON skill (user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_skill_user_name ON skill (user_id, name);
CREATE INDEX IF NOT EXISTS idx_skill_sync_due ON skill (sync_state, next_attempt_at);
