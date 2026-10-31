-- =============================================
-- Create speech_script
--
-- A persisted, replayable read-aloud plan: the *spoken* version of some source
-- text plus a snapshot of the model parameters that produced it.
--
-- The table is deliberately polymorphic. `target_type` says what the text came
-- from (`session_message`, `manual_text`, `knowledge_article`, `tool_call`, …),
-- `target_id` points at that entity, and `session_id` is a query-only
-- denormalization for the per-session list. No foreign keys: the targets live
-- in the session / message / knowledge tables, and deleting a target must
-- archive its scripts (status=ARCHIVED) instead of blocking the delete.
--
-- `source_digest` is the fidelity anchor. An edit is re-validated against the
-- stored `source_text`, and a script is only reusable while the digest still
-- matches the text the caller is about to speak — otherwise a regenerated
-- reply would be read from a script written for the old one.
--
-- `segments_json` holds the SpeechScript segments verbatim and is NEVER selected
-- by list queries (a long plan is tens of KB); list columns are all summary.
--
-- `tts_params_json` / `direction_params_json` are snapshots, never references:
-- model rows get retyped, re-pointed or disabled, so a snapshot is the only way
-- to explain "why did it sound like that". Credentials (api_key, the full
-- base_url) are never written to either column — only the host is kept.
--
-- Status: AUTO = machine-saved (one per target, overwritable, expirable);
-- PINNED = the user asked to keep it; ARCHIVED = soft-deleted.
--
-- `audio_*` columns are reserved for a v2 that caches the rendered whole-plan
-- MP3 in object storage; v1 always writes NULL, so enabling it needs no DDL.
-- Timestamps are epoch millis. Ownership is strict `user_id`; `owners_json` is
-- pre-seeded for a future group-sharing policy and currently holds one element.
-- =============================================

CREATE TABLE speech_script (
    id                    VARCHAR(255) PRIMARY KEY,
    user_id               VARCHAR(255) NOT NULL,
    owners_json           TEXT,

    target_type           VARCHAR(32)  NOT NULL,
    target_id             VARCHAR(255),
    session_id            VARCHAR(255),

    title                 VARCHAR(200) NOT NULL,
    source_text           TEXT         NOT NULL,
    source_digest         VARCHAR(64)  NOT NULL,
    source_chars          INT          NOT NULL,
    spoken_chars          INT          NOT NULL,

    mode                  VARCHAR(16)  NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    revision              INT          NOT NULL,
    segment_count         INT          NOT NULL,

    style                 VARCHAR(32)  NOT NULL,
    mood                  VARCHAR(200),
    segments_json         TEXT         NOT NULL,
    warnings_json         TEXT,

    tts_params_json       TEXT         NOT NULL,
    direction_params_json TEXT,

    audio_key             VARCHAR(512),
    audio_mime            VARCHAR(64),
    audio_bytes           BIGINT,
    audio_duration_ms     INT,

    is_favorite           BOOLEAN      NOT NULL,
    play_count            INT          NOT NULL,
    last_played_at        BIGINT,

    created_at            BIGINT       NOT NULL,
    updated_at            BIGINT       NOT NULL
);

CREATE INDEX idx_speech_script_owner_updated ON speech_script (user_id, updated_at);
CREATE INDEX idx_speech_script_target        ON speech_script (target_type, target_id);
CREATE INDEX idx_speech_script_session       ON speech_script (user_id, session_id);
CREATE INDEX idx_speech_script_digest        ON speech_script (source_digest);
