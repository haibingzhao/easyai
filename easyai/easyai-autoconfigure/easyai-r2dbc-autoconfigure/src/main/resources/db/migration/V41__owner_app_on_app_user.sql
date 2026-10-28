-- =============================================
-- Move application ownership from rows to accounts
--
-- Several applications may share one framework database. Per-row `owner_app` columns on
-- async_task/watch/project were the first attempt; ownership now lives on the account instead:
-- a row belongs to the application its user_id's account belongs to. Background passes join
-- user_id -> app_user.owner_app, so a second application on the same database neither fires the
-- other's watches/schedules, parks its running tasks, nor releases its temporary workspaces.
--
-- The account column is added here so fresh databases (PostgreSQL, H2 file for desktop) are
-- complete after migration, and it is constrained non-null in the same script: the application is
-- what makes an account addressable, so a row without one has neither a login path nor background
-- work. Existing deployments therefore backfill it before this migration runs -- add the column and
-- fill it in with psql during the upgrade window. Unassigned rows then make V41 fail loudly at
-- startup instead of letting accounts silently stop authenticating.
--
-- Usernames are now namespaced per application: two applications may each hold the same name.
--
-- Additive/destructive per the new model; statements restricted to what both H2 (MODE=MYSQL)
-- and PostgreSQL accept. V37 stays as history and is not edited.
-- =============================================

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS owner_app VARCHAR(64);
ALTER TABLE app_user ALTER COLUMN owner_app SET NOT NULL;

DROP INDEX IF EXISTS idx_app_user_username;
CREATE UNIQUE INDEX IF NOT EXISTS ux_app_user_owner_username ON app_user (owner_app, username);

DROP INDEX IF EXISTS idx_async_task_owner_status;
DROP INDEX IF EXISTS idx_watch_owner_due;

ALTER TABLE async_task DROP COLUMN IF EXISTS owner_app;
ALTER TABLE watch      DROP COLUMN IF EXISTS owner_app;
ALTER TABLE project    DROP COLUMN IF EXISTS owner_app;

-- The old due index was (owner_app, status, next_due_at); the scan is now app-scoped through
-- app_user, so status/next_due_at is the index that still helps.
CREATE INDEX IF NOT EXISTS idx_watch_status_due ON watch (status, next_due_at);
