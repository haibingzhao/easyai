-- Null revision and zone identify legacy settings for one coalesced startup catch-up.
ALTER TABLE scheduled_task_setting ADD COLUMN IF NOT EXISTS next_fire_at BIGINT NULL;
ALTER TABLE scheduled_task_setting ADD COLUMN IF NOT EXISTS schedule_revision VARCHAR(64) NULL;
ALTER TABLE scheduled_task_setting ADD COLUMN IF NOT EXISTS schedule_zone VARCHAR(64) NULL;

CREATE INDEX IF NOT EXISTS idx_scheduled_task_setting_due
    ON scheduled_task_setting (enabled, task_type, next_fire_at);
