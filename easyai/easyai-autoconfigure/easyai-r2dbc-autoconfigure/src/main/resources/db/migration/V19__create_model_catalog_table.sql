-- =============================================
-- Global model catalog
--
-- Vendor-published facts (capabilities, token limits, pricing, description) keyed by the API
-- model id, used to prefill the add-model form and for future cost-aware model selection.
-- Shared across users (no user_id column); rows are written only by catalog refreshes and
-- rows retired upstream are intentionally kept. `raw_payload` keeps the vendor item JSON as
-- fetched so mapping evolution can replay stored data without a network round-trip.
--
-- Keep statements restricted to types both H2 (MODE=MYSQL) and PostgreSQL accept,
-- mirroring the verified `aux_model_settings` definition in V8.
-- =============================================

CREATE TABLE IF NOT EXISTS model_catalog (
    model_id VARCHAR(255) PRIMARY KEY,
    vendor VARCHAR(64) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    description TEXT,
    capabilities TEXT,
    default_options TEXT,
    pricing TEXT,
    source VARCHAR(64) NOT NULL,
    raw_payload TEXT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
