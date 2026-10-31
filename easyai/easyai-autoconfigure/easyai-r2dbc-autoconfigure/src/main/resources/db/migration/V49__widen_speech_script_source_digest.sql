-- =============================================
-- Widen speech_script.source_digest to fit the digest it actually stores.
--
-- V48 sized the column at 64, which is the length of the bare SHA-256 hex digest
-- only. `SpokenTextNormalizer.sourceDigest` writes the algorithm tag too —
-- `sha256:` + 64 hex = 71 characters — so every archive write was rejected by
-- the column length check ("exceeds length (71 > 64)").
--
-- The failure was invisible by design: `SpeechScriptArchiveService.saveAuto`
-- treats a failed write as a lost convenience rather than a lost read, so
-- playback worked while the Read Aloud library stayed empty.
--
-- 80 rather than 71 on purpose: a future digest algorithm (or a different tag)
-- must not need another migration. The index on the column is unchanged.
-- =============================================

ALTER TABLE speech_script
    ALTER COLUMN source_digest SET DATA TYPE VARCHAR(80);
