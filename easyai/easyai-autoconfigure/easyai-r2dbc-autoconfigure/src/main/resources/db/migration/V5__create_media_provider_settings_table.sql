-- =============================================
-- Per-user media-generation provider credentials
--
-- One row per (user, service_kind), edited from the frontend Settings page and applied without a
-- restart. This table is the only source of media-provider configuration; there are no media
-- properties. Credentials stay server-side; read endpoints mask them.
--
-- Deliberately separate from `model_provider_config`, which feeds the ReAct ChatModel only —
-- generation models (speech/image/video) are reached through tools, not the chat loop.
--
-- Keep statements restricted to types both H2 (MODE=MYSQL) and PostgreSQL accept,
-- mirroring the verified `storage_settings` definition in V4.
-- =============================================

CREATE TABLE IF NOT EXISTS media_provider_settings (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL DEFAULT 'system',
    service_kind VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    provider_type VARCHAR(32) NOT NULL DEFAULT 'openai',
    base_url VARCHAR(512) NOT NULL DEFAULT '',
    region VARCHAR(64) NOT NULL DEFAULT '',
    api_key VARCHAR(1024),
    access_key_id VARCHAR(256) NOT NULL DEFAULT '',
    access_key_secret VARCHAR(1024),
    default_model VARCHAR(128) NOT NULL DEFAULT '',
    options TEXT,
    timeout_seconds BIGINT NOT NULL DEFAULT 600,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_media_provider_user_kind ON media_provider_settings (user_id, service_kind);
