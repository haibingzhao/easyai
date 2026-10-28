-- =============================================
-- Knowledge tree schema (destructive)
--
-- Folders become the only persisted hierarchy; articles reference a folder and are addressed by
-- their UUID, which is also the EasyRAG key and the object-storage key. No legacy columns are
-- carried over: pre-release knowledge data is wiped with psql before this version starts.
-- =============================================

DROP TABLE IF EXISTS knowledge_article CASCADE;
DROP TABLE IF EXISTS knowledge_folder CASCADE;

CREATE TABLE knowledge_folder (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    parent_id VARCHAR(255) NOT NULL DEFAULT '',
    name VARCHAR(255) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_by VARCHAR(255),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_kf_bucket_parent_name ON knowledge_folder (user_id, parent_id, name);
CREATE INDEX IF NOT EXISTS idx_kf_user_parent ON knowledge_folder (user_id, parent_id);

CREATE TABLE knowledge_article (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    created_by VARCHAR(255),
    folder_id VARCHAR(255) NOT NULL,
    file_name VARCHAR(255),
    title VARCHAR(255) NOT NULL DEFAULT '',
    description TEXT,
    category VARCHAR(64) NOT NULL DEFAULT 'other',
    ext VARCHAR(16) NOT NULL DEFAULT 'md',
    origin VARCHAR(32) NOT NULL,
    origin_session_id VARCHAR(255),
    origin_message_id VARCHAR(255),
    composed_from TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    rag_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ka_folder_file ON knowledge_article (user_id, folder_id, file_name);
CREATE INDEX IF NOT EXISTS idx_ka_user_folder ON knowledge_article (user_id, folder_id);
CREATE INDEX IF NOT EXISTS idx_ka_user_status ON knowledge_article (user_id, status);
CREATE INDEX IF NOT EXISTS idx_ka_user_rag ON knowledge_article (user_id, rag_enabled);
