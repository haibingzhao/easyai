-- Session variables: JSON-serialized map of key-value pairs that persist across compaction and resume.
ALTER TABLE session ADD COLUMN IF NOT EXISTS variables_json TEXT;
