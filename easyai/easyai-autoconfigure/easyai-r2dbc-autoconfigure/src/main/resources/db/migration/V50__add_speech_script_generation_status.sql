-- =============================================
-- Track whether a read-aloud script finished planning.
--
-- This is independent of status (AUTO/PINNED/ARCHIVED): the first says whether
-- the script is complete, the second controls its library lifecycle. Only
-- COMPLETED rows may be reused. Existing rows stay NULL because their planning
-- outcome is unknown, so the first read after upgrading plans them again.
-- IN_PROGRESS rows left by a crashed process are likewise never reused.
-- =============================================

ALTER TABLE speech_script
    ADD COLUMN generation_status VARCHAR(16);
