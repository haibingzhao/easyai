-- =============================================
-- Backfill the `watch` tool onto the built-in default agent.
--
-- The seed initializer only writes tool bindings when it creates an agent, so an existing install
-- never picks up a newly added default tool. Letting the model create watches therefore needs this
-- one row. Scoped to the built-in default agent on purpose: Ask is read-only and custom agents are
-- the user's choice — the agent editor is where those get `watch`.
--
-- `WHERE NOT EXISTS` (rather than an ON CONFLICT) keeps the script re-runnable on both PostgreSQL
-- and the H2 file database, which do not share an upsert syntax here.
-- =============================================

INSERT INTO agent_tool (id, agent_id, user_id, target_type, target_name)
SELECT 'seed-default-agent-watch', 'default-agent', 'system', 'TOOL', 'watch'
WHERE NOT EXISTS (
    SELECT 1 FROM agent_tool
    WHERE agent_id = 'default-agent'
      AND user_id = 'system'
      AND target_type = 'TOOL'
      AND target_name = 'watch'
);
