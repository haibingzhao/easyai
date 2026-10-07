-- =============================================
-- Session Tags
--
-- Join table for multi-tag sessions: one row per (session, tag). Enables index-backed OR
-- filtering by tag and cheap DISTINCT aggregation for the tag autocomplete / filter row.
-- user_id is denormalized from the owning session for strict multi-user isolation.
--
-- Keep statements restricted to types both H2 (MODE=MYSQL) and PostgreSQL accept,
-- mirroring the verified definitions in V1/V3.
-- =============================================

CREATE TABLE IF NOT EXISTS session_tag (
    session_id VARCHAR(255) NOT NULL,
    tag        VARCHAR(128) NOT NULL,
    user_id    VARCHAR(255) NOT NULL DEFAULT 'system',
    PRIMARY KEY (session_id, tag)
);

-- session_id needs no separate index: PRIMARY KEY (session_id, tag) already covers
-- session_id lookups via its leftmost prefix.
CREATE INDEX IF NOT EXISTS idx_session_tag_tag  ON session_tag (tag);
CREATE INDEX IF NOT EXISTS idx_session_tag_user ON session_tag (user_id);
