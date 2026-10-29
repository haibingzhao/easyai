-- =============================================
-- Backfill message.seq and session.message_seq
--
-- Correlated subquery rather than a window function: H2 (MODE=MYSQL) and PostgreSQL must
-- both run this. Sessions hold hundreds to a few thousand messages, so O(n^2) per session
-- is acceptable; total runtime scales with the message row count.
--
-- The ordering expression is the one every reader used before this column existed, so the
-- backfilled seq reproduces the pre-migration order exactly (id as the millisecond tie-break).
-- Offline, one-time upgrade: stop every shared-database writer for V43/V44 and restart only upgraded hosts; never rerun.
-- =============================================

UPDATE message m
SET seq = (
    SELECT COUNT(*) FROM message m2
    WHERE m2.session_id = m.session_id
      AND (m2.created_at < m.created_at
           OR (m2.created_at = m.created_at AND m2.id <= m.id))
)
WHERE m.seq IS NULL;

UPDATE session s
SET message_seq = COALESCE((SELECT MAX(seq) FROM message m WHERE m.session_id = s.id), 0);
