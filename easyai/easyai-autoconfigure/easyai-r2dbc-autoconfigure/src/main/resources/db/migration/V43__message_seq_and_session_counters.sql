-- =============================================
-- Message seq + session counters
--
-- Ordering cannot be keyed on (created_at, id): forking re-generates message ids
-- (UUID.randomUUID()), which reshuffles rows sharing a millisecond. `seq` is the
-- session-scoped ordinal that stays causal across forks.
--
-- `session.message_seq` hands out seq in the inserting transaction (no MAX()+1 race),
-- `session.content_revision` counts history REWRITES only (cache invalidation key).
--
-- Column types kept to what both H2 (MODE=MYSQL) and PostgreSQL accept.
-- =============================================

ALTER TABLE message ADD COLUMN IF NOT EXISTS seq BIGINT NULL;

ALTER TABLE session ADD COLUMN IF NOT EXISTS message_seq BIGINT NOT NULL DEFAULT 0;
ALTER TABLE session ADD COLUMN IF NOT EXISTS content_revision BIGINT NOT NULL DEFAULT 0;

-- Unique: doubles as the range-scan index and turns a seq-allocation bug into a loud
-- constraint violation instead of silently duplicated ordinals.
CREATE UNIQUE INDEX IF NOT EXISTS uq_message_session_seq ON message (session_id, seq);
