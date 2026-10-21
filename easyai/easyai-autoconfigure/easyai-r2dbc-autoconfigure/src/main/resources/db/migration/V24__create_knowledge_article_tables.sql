-- =============================================
-- Knowledge article metadata + document-level access control
--
-- OSS stores the article content (source of truth); EasyRAG holds the derived vector index.
-- This table tracks metadata (title, category, visibility, origin) and powers the article
-- listing page with permission-aware queries.
--
-- `visibility` controls the default read scope:
--   'private' — only the creator (+ explicitly granted users) can read
--   'group'   — all members of the creator's group can read
-- Write access always requires ownership or an explicit 'write' grant.
--
-- knowledge_article_access stores per-document ACL grants.
-- =============================================

CREATE TABLE IF NOT EXISTS knowledge_article (
    id                  VARCHAR(255)  NOT NULL,
    user_id             VARCHAR(255)  NOT NULL DEFAULT 'system',
    knowledge_key       VARCHAR(512)  NOT NULL,
    oss_key             VARCHAR(1024) NOT NULL DEFAULT '',
    title               VARCHAR(255)  NOT NULL DEFAULT '',
    description         TEXT,
    category            VARCHAR(64)   NOT NULL DEFAULT 'other',
    source              VARCHAR(255)  NOT NULL,
    relative_path       VARCHAR(512)  NOT NULL,
    ext                 VARCHAR(16)   NOT NULL DEFAULT 'md',
    content_hash        VARCHAR(64),
    visibility          VARCHAR(16)   NOT NULL DEFAULT 'private',
    origin              VARCHAR(32)   NOT NULL DEFAULT 'upload',
    origin_session_id   VARCHAR(255),
    origin_message_id   VARCHAR(255),
    composed_from       TEXT,
    created_at          BIGINT        NOT NULL,
    updated_at          BIGINT        NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS udx_ka_user_key ON knowledge_article (user_id, knowledge_key);
CREATE INDEX IF NOT EXISTS idx_ka_user_source ON knowledge_article (user_id, source);
CREATE INDEX IF NOT EXISTS idx_ka_user_origin ON knowledge_article (user_id, origin);
CREATE INDEX IF NOT EXISTS idx_ka_visibility ON knowledge_article (user_id, visibility);

CREATE TABLE IF NOT EXISTS knowledge_article_access (
    id              VARCHAR(255)  NOT NULL,
    article_id      VARCHAR(255)  NOT NULL,
    grantee_id      VARCHAR(255)  NOT NULL,
    permission      VARCHAR(16)   NOT NULL DEFAULT 'read',
    granted_by      VARCHAR(255)  NOT NULL,
    granted_at      BIGINT        NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS udx_kaa_article_grantee ON knowledge_article_access (article_id, grantee_id);
CREATE INDEX IF NOT EXISTS idx_kaa_grantee ON knowledge_article_access (grantee_id);
