-- =============================================
-- Widen app_user.avatar so it can hold a real avatar reference
--
-- The column used to carry nothing but a preset id ("avatar-1"). It now carries exactly one of
-- three short strings, all validated in AuthService before the row is written:
--   * "avatar-1"                    -> preset placeholder; the UI renders coloured initials
--   * "avatars/{owner}/{uuid}.png"  -> object key, served by GET /api/media/file?key=
--   * "https://host/path.png"       -> user-supplied image URL, rendered directly by the browser
-- Image bytes never live here: the service rejects a data: URL, and the longest key we generate
-- is ~85 characters, so 512 is ample.
--
-- Widen in place instead of drop-and-re-add: re-applying the statement is a harmless no-op, which
-- is the replay property every other script in this folder relies on (see the skill replay case in
-- FlywayMigrationRunnerTest), and it can never destroy an avatar a user has already set.
-- `ALTER COLUMN ... SET DATA TYPE` is the spelling H2 (also under MODE=MYSQL) and PostgreSQL share;
-- both keep the NOT NULL and DEFAULT of the column when only its type is restated.
-- =============================================

ALTER TABLE app_user ALTER COLUMN avatar SET DATA TYPE VARCHAR(512);
