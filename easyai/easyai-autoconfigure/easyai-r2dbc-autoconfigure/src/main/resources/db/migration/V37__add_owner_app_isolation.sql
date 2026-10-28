-- =============================================
-- Per-application ownership for rows background passes act on
--
-- Several applications may share one framework database. Three of them used to assume a single
-- process owned it: the startup orphan sweep parked every non-terminal task (including another
-- host's live ones), the watch scheduler claimed any due row (running it with a toolset that host
-- could not resolve), and the temp-workspace sweep released any scratch directory.
--
-- owner_app records which application (`spring.application.name`) owns a row:
--   async_task — the host that runs it; only that host parks it as INTERRUPTED at startup
--   watch      — the host that fires it; only that host scans it as due
--   project    — the host that created a temp workspace; only that host releases it
--
-- THIS MIGRATION DOES NOT BACKFILL. The right value depends on the deployment, so it cannot be
-- baked into a shared script. Unowned rows (owner_app IS NULL) are deliberately inert: no host
-- fires, parks or releases them. Two supported ways to settle them:
--   1. `easyai.host.adopt-legacy-rows=true` (default) — each application claims unowned rows at
--      startup. Correct for a single-application deployment. When several share the database,
--      exactly one application may keep it on, or
--   2. assign them by hand before upgrading (documented in the release notes):
--        UPDATE async_task SET owner_app = '<app>' WHERE owner_app IS NULL;
--        UPDATE watch      SET owner_app = '<app>' WHERE owner_app IS NULL;
--        UPDATE project    SET owner_app = '<app>' WHERE owner_app IS NULL AND kind = 'temp';
--      With adoption off everywhere, a host logs the unowned counts and this SQL at startup.
--
-- Additive only; statements restricted to what both H2 (MODE=MYSQL) and PostgreSQL accept.
-- =============================================

ALTER TABLE async_task ADD COLUMN IF NOT EXISTS owner_app VARCHAR(64);
ALTER TABLE watch      ADD COLUMN IF NOT EXISTS owner_app VARCHAR(64);
ALTER TABLE project    ADD COLUMN IF NOT EXISTS owner_app VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_async_task_owner_status ON async_task (owner_app, status);
CREATE INDEX IF NOT EXISTS idx_watch_owner_due ON watch (owner_app, status, next_due_at);
