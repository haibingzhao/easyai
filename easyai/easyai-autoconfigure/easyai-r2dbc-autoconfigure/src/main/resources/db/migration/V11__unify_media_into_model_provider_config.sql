-- =============================================
-- Unify media-generation configuration into model_provider_config
--
-- The former `media_provider_settings` table (one row per user+kind, AK/SK auth) is retired:
-- generation models are now `model_provider_config` rows partitioned by `model_type`
-- (CHAT / IMAGE / VIDEO / SPEECH / MUSIC / ASR), sharing the group credential mechanism and
-- apiKey-only auth with chat models. `media_options` carries provider-specific generation
-- parameters as a raw JSON object; `is_default` marks one fallback entry per user+model_type.
--
-- Pre-release: stored media rows are NOT migrated — users reconfigure on the Models page.
--
-- Keep statements restricted to types both H2 (MODE=MYSQL) and PostgreSQL accept,
-- mirroring the verified `storage_settings` definition in V4.
-- =============================================

ALTER TABLE model_provider_config ADD COLUMN IF NOT EXISTS model_type VARCHAR(32) NOT NULL DEFAULT 'CHAT';
ALTER TABLE model_provider_config ADD COLUMN IF NOT EXISTS media_options TEXT;
ALTER TABLE model_provider_config ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE;

DROP TABLE IF EXISTS media_provider_settings;
