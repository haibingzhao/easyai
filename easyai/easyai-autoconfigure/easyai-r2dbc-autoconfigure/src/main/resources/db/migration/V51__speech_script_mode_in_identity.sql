-- =============================================
-- Keep the refined read and the directed plan of one reply in two rows.
--
-- `mode` joins the identity of an AUTO script: `speech_script` now holds at
-- most one AUTO row per (user, target, mode) instead of one per (user, target).
-- Before this, "refine this reply" then "direct this reply" overwrote the same
-- row, so the library showed a single entry whose label flipped to Directed —
-- and because the reuse query did not filter by mode either, the directed read
-- was answered from the refined single segment instead of planning again.
--
-- The index mirrors the two queries that decide this: `saveAuto`'s
-- "which row do I replace" and `findReusable`'s "which row may I speak".
-- `target_id` is nullable (manual text), so the index keeps NULLs distinct,
-- which is what the manual-text target needs.
-- =============================================

CREATE INDEX idx_speech_script_target_mode ON speech_script (user_id, target_type, target_id, mode);
