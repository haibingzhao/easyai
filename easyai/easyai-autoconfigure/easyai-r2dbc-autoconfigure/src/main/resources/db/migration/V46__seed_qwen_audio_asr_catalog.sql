-- =============================================
-- Seed ASR capabilities into the model catalog.
--
-- Authoring copy hand-maintained alongside `AsrBuiltinCatalog`; the built-in table is the runtime
-- fallback, so keep the two in step when a vendor fact changes.
--
-- Scope: qwen-audio-3.0-asr-flash-streaming / qwen-audio-3.1-asr-flash-streaming /
--        qwen-audio-3.1-asr-flash-message / qwen-audio-3.0-asr-flash /
--        qwen-audio-3.1-asr-flash / qwen-audio-3.1-asr-flash-filetrans
--
-- The block lives in the existing `speech_capabilities` column as an `asr` sibling of the
-- synthesis fields, because both are hand-maintained vendor facts that a catalog refresh must
-- preserve. The column is re-declared defensively (`IF NOT EXISTS`) so this migration also applies
-- to a database seeded before V45, and no TTS block is touched: each statement below is scoped to an
-- ASR model id, which V45 never seeds.
--
-- Project defaults differ from the vendor defaults on purpose (short chat dictation, not meeting
-- transcription). Each such parameter carries `vendorDefault` + `note` so the form can say so
-- instead of quietly disagreeing with the documentation:
--   max_sentence_silence  project 400 ms  / doc 800 ms
--   vad_model             project near     / doc far_field_meeting_16k
-- `locked` means the product fixes the value for chat dictation; the form shows it read-only and
-- never writes it into `media_options`.
--
-- These rows are not a usability claim: whether a model is reachable at all is decided at runtime
-- by the endpoint trust check and, before that, by the P0 protocol probe.
--
-- Idempotent and re-runnable: UPDATE first, INSERT only when absent (no shared upsert syntax
-- between PostgreSQL and the H2 file database).
--
-- IMMUTABLE ONCE APPLIED. A capability fix after this version has run anywhere must ship as V47+.
-- =============================================

ALTER TABLE model_catalog ADD COLUMN IF NOT EXISTS speech_capabilities TEXT;

-- ---------- qwen-audio-3.0-asr-flash-streaming ----------
-- 3.0 只接受 max_sentence_silence；vad_model / keep_dialect 官方标注仅 3.1

UPDATE model_catalog
   SET speech_capabilities = '{"asr":{"channel":"websocket","docsUrl":"https://docs.bailian.console.aliyun.com/zh/model-studio/qwen-audio-asr-streaming-websocket-api","audio":{"formats":["pcm"],"sampleRates":[16000]},"params":{"max_sentence_silence":{"type":"int","min":200,"max":6000,"unit":"ms","default":400,"vendorDefault":800,"note":"短段口述先断句；文档默认 800ms"},"language_hints":{"type":"string[]","maxItems":4,"finalOnly":true},"vocabulary":{"type":"map","maxSuper":50,"maxMerged":2000},"vocabulary_id":{"type":"string"}},"features":{"context":"initial"}}}',
       updated_at = 1793318400000
 WHERE model_id = 'qwen-audio-3.0-asr-flash-streaming';

INSERT INTO model_catalog
    (model_id, vendor, display_name, description, capabilities, default_options,
     reasoning_profile, pricing, speech_capabilities, source, raw_payload, created_at, updated_at)
SELECT 'qwen-audio-3.0-asr-flash-streaming', 'bailian', 'Qwen-Audio-3.0 ASR Flash Streaming',
       'Qwen-Audio 3.0 实时语音识别（WebSocket，边说边出字）',
       NULL, NULL, NULL, NULL,
       '{"asr":{"channel":"websocket","docsUrl":"https://docs.bailian.console.aliyun.com/zh/model-studio/qwen-audio-asr-streaming-websocket-api","audio":{"formats":["pcm"],"sampleRates":[16000]},"params":{"max_sentence_silence":{"type":"int","min":200,"max":6000,"unit":"ms","default":400,"vendorDefault":800,"note":"短段口述先断句；文档默认 800ms"},"language_hints":{"type":"string[]","maxItems":4,"finalOnly":true},"vocabulary":{"type":"map","maxSuper":50,"maxMerged":2000},"vocabulary_id":{"type":"string"}},"features":{"context":"initial"}}}',
       'bailian-asr-doc', NULL, 1793318400000, 1793318400000
WHERE NOT EXISTS (SELECT 1 FROM model_catalog WHERE model_id = 'qwen-audio-3.0-asr-flash-streaming');

-- ---------- qwen-audio-3.1-asr-flash-streaming ----------

UPDATE model_catalog
   SET speech_capabilities = '{"asr":{"channel":"websocket","docsUrl":"https://docs.bailian.console.aliyun.com/zh/model-studio/qwen-audio-asr-streaming-websocket-api","audio":{"formats":["pcm"],"sampleRates":[16000]},"params":{"vad_model":{"type":"enum","options":["near_meeting_16k","far_field_meeting_16k"],"default":"near_meeting_16k","vendorDefault":"far_field_meeting_16k","note":"聊天口述按近场采集；文档默认远场"},"max_sentence_silence":{"type":"int","min":200,"max":6000,"unit":"ms","default":400,"vendorDefault":800,"note":"短段口述先断句；文档默认 800ms"},"keep_dialect":{"type":"bool","default":false,"finalOnly":true},"language_hints":{"type":"string[]","maxItems":4,"finalOnly":true},"vocabulary":{"type":"map","maxSuper":50,"maxMerged":2000},"vocabulary_id":{"type":"string"}},"features":{"context":"initial"}}}',
       updated_at = 1793318400000
 WHERE model_id = 'qwen-audio-3.1-asr-flash-streaming';

INSERT INTO model_catalog
    (model_id, vendor, display_name, description, capabilities, default_options,
     reasoning_profile, pricing, speech_capabilities, source, raw_payload, created_at, updated_at)
SELECT 'qwen-audio-3.1-asr-flash-streaming', 'bailian', 'Qwen-Audio-3.1 ASR Flash Streaming',
       'Qwen-Audio 3.1 实时语音识别（WebSocket，近/远场 VAD）',
       NULL, NULL, NULL, NULL,
       '{"asr":{"channel":"websocket","docsUrl":"https://docs.bailian.console.aliyun.com/zh/model-studio/qwen-audio-asr-streaming-websocket-api","audio":{"formats":["pcm"],"sampleRates":[16000]},"params":{"vad_model":{"type":"enum","options":["near_meeting_16k","far_field_meeting_16k"],"default":"near_meeting_16k","vendorDefault":"far_field_meeting_16k","note":"聊天口述按近场采集；文档默认远场"},"max_sentence_silence":{"type":"int","min":200,"max":6000,"unit":"ms","default":400,"vendorDefault":800,"note":"短段口述先断句；文档默认 800ms"},"keep_dialect":{"type":"bool","default":false,"finalOnly":true},"language_hints":{"type":"string[]","maxItems":4,"finalOnly":true},"vocabulary":{"type":"map","maxSuper":50,"maxMerged":2000},"vocabulary_id":{"type":"string"}},"features":{"context":"initial"}}}',
       'bailian-asr-doc', NULL, 1793318400000, 1793318400000
WHERE NOT EXISTS (SELECT 1 FROM model_catalog WHERE model_id = 'qwen-audio-3.1-asr-flash-streaming');

-- ---------- qwen-audio-3.1-asr-flash-message ----------
-- 默认只回句子 final，intermediate_result_enabled=true 才有中间结果；该值由产品固定（locked）

UPDATE model_catalog
   SET speech_capabilities = '{"asr":{"channel":"websocket","docsUrl":"https://docs.bailian.console.aliyun.com/zh/model-studio/qwen-asr-message-websocket-api","audio":{"formats":["pcm"],"sampleRates":[16000]},"params":{"intermediate_result_enabled":{"type":"bool","default":true,"locked":true},"disfluency_removal_enabled":{"type":"bool","default":false},"vad_model":{"type":"enum","options":["near_meeting_16k","far_field_meeting_16k"],"default":"near_meeting_16k","vendorDefault":"far_field_meeting_16k","note":"聊天口述按近场采集；文档默认远场"},"max_sentence_silence":{"type":"int","min":200,"max":6000,"unit":"ms","default":400,"vendorDefault":800,"note":"短段口述先断句；文档默认 800ms"},"keep_dialect":{"type":"bool","default":false,"finalOnly":true},"language_hints":{"type":"string[]","maxItems":4,"finalOnly":true},"vocabulary":{"type":"map","maxSuper":50,"maxMerged":2000},"vocabulary_id":{"type":"string"}},"features":{"context":"initial","continueTask":true}}}',
       updated_at = 1793318400000
 WHERE model_id = 'qwen-audio-3.1-asr-flash-message';

INSERT INTO model_catalog
    (model_id, vendor, display_name, description, capabilities, default_options,
     reasoning_profile, pricing, speech_capabilities, source, raw_payload, created_at, updated_at)
SELECT 'qwen-audio-3.1-asr-flash-message', 'bailian', 'Qwen-Audio-3.1 ASR Flash Message',
       'Qwen-Audio 3.1 短段实时识别（WebSocket，适合语音输入法）',
       NULL, NULL, NULL, NULL,
       '{"asr":{"channel":"websocket","docsUrl":"https://docs.bailian.console.aliyun.com/zh/model-studio/qwen-asr-message-websocket-api","audio":{"formats":["pcm"],"sampleRates":[16000]},"params":{"intermediate_result_enabled":{"type":"bool","default":true,"locked":true},"disfluency_removal_enabled":{"type":"bool","default":false},"vad_model":{"type":"enum","options":["near_meeting_16k","far_field_meeting_16k"],"default":"near_meeting_16k","vendorDefault":"far_field_meeting_16k","note":"聊天口述按近场采集；文档默认远场"},"max_sentence_silence":{"type":"int","min":200,"max":6000,"unit":"ms","default":400,"vendorDefault":800,"note":"短段口述先断句；文档默认 800ms"},"keep_dialect":{"type":"bool","default":false,"finalOnly":true},"language_hints":{"type":"string[]","maxItems":4,"finalOnly":true},"vocabulary":{"type":"map","maxSuper":50,"maxMerged":2000},"vocabulary_id":{"type":"string"}},"features":{"context":"initial","continueTask":true}}}',
       'bailian-asr-doc', NULL, 1793318400000, 1793318400000
WHERE NOT EXISTS (SELECT 1 FROM model_catalog WHERE model_id = 'qwen-audio-3.1-asr-flash-message');

-- ---------- 非实时（HTTP）与文件模型 ----------
-- channel=http：走现有 /api/asr/transcribe；channel=none：不能用于麦克风录入

UPDATE model_catalog
   SET speech_capabilities = '{"asr":{"channel":"http","audio":{"formats":["pcm"],"sampleRates":[16000]}}}',
       updated_at = 1793318400000
 WHERE model_id = 'qwen-audio-3.0-asr-flash';

UPDATE model_catalog
   SET speech_capabilities = '{"asr":{"channel":"http","audio":{"formats":["pcm"],"sampleRates":[16000]}}}',
       updated_at = 1793318400000
 WHERE model_id = 'qwen-audio-3.1-asr-flash';

UPDATE model_catalog
   SET speech_capabilities = '{"asr":{"channel":"none"}}',
       updated_at = 1793318400000
 WHERE model_id = 'qwen-audio-3.1-asr-flash-filetrans';
