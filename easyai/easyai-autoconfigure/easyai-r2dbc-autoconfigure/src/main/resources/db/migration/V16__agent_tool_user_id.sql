-- =============================================
-- Owner scoping for the agent whitelist table
--
-- `agent.id` is only unique per owner bucket (the agent PK is `(id, user_id)`), but `agent_tool`
-- keyed its rows by `agent_id` alone. Two agents sharing an id across buckets — a member's personal
-- agent and their group's shared one, say — therefore shared a single whitelist, and the
-- delete-then-insert save path let whoever wrote last silently overwrite the other's TOOL /
-- SUBAGENT / SKILL / MCP / COMMAND / MEMBER entries.
--
-- `user_id` here is the owner of the *agent row* the whitelist belongs to (always equal to
-- `agent.user_id`), never the requesting caller's. Existing rows are backfilled from the agent
-- table by that rule.
--
-- Keep statements restricted to forms both H2 (MODE=MYSQL) and PostgreSQL accept, mirroring V9.
-- =============================================

ALTER TABLE agent_tool ADD COLUMN IF NOT EXISTS user_id VARCHAR(255) NOT NULL DEFAULT 'system';

-- Backfill from the owning agent row. MAX() keeps the subquery single-valued (and the migration
-- therefore non-fatal) for an agent_id that already collides across buckets; such a row keeps one
-- owner's whitelist and the other bucket starts from an empty one, which is the safe reading of
-- "inherit all". Orphan rows — no matching agent — keep the 'system' default.
UPDATE agent_tool SET user_id = (
    SELECT MAX(a.user_id) FROM agent a WHERE a.id = agent_tool.agent_id
) WHERE EXISTS (
    SELECT 1 FROM agent a WHERE a.id = agent_tool.agent_id
);

-- Replaces idx_agent_tool_agent_id for lookup purposes; the old single-column index is left in
-- place because dropping it is not portable across H2 and PostgreSQL.
CREATE INDEX IF NOT EXISTS idx_agent_tool_agent_user ON agent_tool (agent_id, user_id);
