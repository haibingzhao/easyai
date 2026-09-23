ALTER TABLE skill ADD COLUMN indexed_checksum VARCHAR(64);
ALTER TABLE skill ADD COLUMN sync_state VARCHAR(24) NOT NULL DEFAULT 'PENDING_INDEX';
ALTER TABLE skill ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE skill ADD COLUMN next_attempt_at BIGINT;
ALTER TABLE skill ADD COLUMN last_error VARCHAR(2000);
ALTER TABLE skill ADD COLUMN index_project_path VARCHAR(512);
ALTER TABLE skill ADD COLUMN previous_project_hash VARCHAR(16);
ALTER TABLE skill ADD COLUMN previous_project_path VARCHAR(512);

-- Historical checksums describe disk observations, not confirmed remote processing.
UPDATE skill SET sync_state = 'PENDING_DELETE' WHERE enabled = FALSE;
CREATE INDEX idx_skill_sync_due ON skill(sync_state, next_attempt_at);
