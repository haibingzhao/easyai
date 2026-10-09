-- =============================================
-- Drop orphan columns left on the `skill` table by retired migrations
--
-- PR #34 (the USER-granular skill refactor) reshaped `skill` by editing already-applied
-- migrations in place instead of adding a new one: it deleted V6 (project-scoped identity) and
-- V7 (recoverable sync) outright, and rewrote V3 to drop the `origin` column. Databases that had
-- run the old migrations kept those columns physically, but the current `skill` definition — V3
-- for fresh databases and the Exposed `SkillTable` mapping — no longer declares any of them, so
-- they are dead weight that makes an existing database diverge from a freshly migrated one.
--
-- Every column here is unmapped and unreferenced in code. `IF EXISTS` makes this a no-op on
-- databases that never ran the old migrations (fresh ones), and a real drop on the ones that did.
--
-- Keep statements restricted to forms both H2 (MODE=MYSQL) and PostgreSQL accept.
-- =============================================

ALTER TABLE skill DROP COLUMN IF EXISTS origin;
ALTER TABLE skill DROP COLUMN IF EXISTS project_hash;
ALTER TABLE skill DROP COLUMN IF EXISTS index_project_path;
ALTER TABLE skill DROP COLUMN IF EXISTS previous_project_hash;
ALTER TABLE skill DROP COLUMN IF EXISTS previous_project_path;
