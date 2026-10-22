-- =============================================
-- Thinking / effort matrix per catalog model
--
-- The vendor catalog only exposes a boolean "reasoning" flag, which cannot express whether
-- thinking can be turned off or which effort levels a model accepts. reasoning_profile holds
-- the resolved ReasoningProfile JSON (mode/effortLevels/defaultThinking/defaultEffort/source).
-- =============================================

ALTER TABLE model_catalog ADD COLUMN reasoning_profile TEXT;
