-- =============================================
-- Model Config Group
--
-- Adds the model_config_group table for grouping model configs
-- that share the same provider connection (protocol/baseUrl/apiKey/timeout).
-- model_provider_config keeps a denormalized copy of connection fields
-- and links to its group via group_id (no FK, zero JOIN at runtime).
--
-- Compatible with H2 (MODE=MYSQL) and PostgreSQL.
-- =============================================

CREATE TABLE IF NOT EXISTS model_config_group (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    protocol VARCHAR(64) NOT NULL,
    is_custom BOOLEAN NOT NULL,
    base_url VARCHAR(512),
    api_key TEXT,
    timeout_seconds BIGINT NOT NULL DEFAULT 600,
    user_id VARCHAR(255) NOT NULL DEFAULT 'system',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

ALTER TABLE model_provider_config ADD COLUMN IF NOT EXISTS group_id VARCHAR(255);
