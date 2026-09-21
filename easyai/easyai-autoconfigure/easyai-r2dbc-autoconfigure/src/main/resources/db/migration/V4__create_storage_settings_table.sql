-- =============================================
-- Per-user object storage settings
--
-- One row per owner, edited from the frontend Settings page and applied without a restart.
-- This table is the only source of storage configuration; there are no storage properties.
-- Credentials stay server-side; read endpoints mask them.
--
-- Keep statements restricted to types both H2 (MODE=MYSQL) and PostgreSQL accept,
-- mirroring the verified `skill` definition in V3.
-- =============================================

CREATE TABLE IF NOT EXISTS storage_settings (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL DEFAULT 'system',
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    storage_type VARCHAR(16) NOT NULL DEFAULT 'aliyun',
    endpoint VARCHAR(256) NOT NULL DEFAULT '',
    bucket VARCHAR(256) NOT NULL DEFAULT '',
    access_key_id VARCHAR(256) NOT NULL DEFAULT '',
    access_key_secret VARCHAR(512) NOT NULL DEFAULT '',
    local_dir VARCHAR(512) NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_storage_settings_user ON storage_settings (user_id);
