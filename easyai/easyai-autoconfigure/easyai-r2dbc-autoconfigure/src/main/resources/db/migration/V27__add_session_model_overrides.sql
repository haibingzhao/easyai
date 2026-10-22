-- =============================================
-- Per-session model option overrides
--
-- The composer's model picker lets the user override effort and the working context window for
-- one model. The preference lives in the browser; this column records the values actually applied
-- to the session so resume/answer paths and the history token bar keep the same window.
-- =============================================

ALTER TABLE session ADD COLUMN model_overrides TEXT;
