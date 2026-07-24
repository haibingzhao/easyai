-- =============================================
-- Agent Composite Primary Key
--
-- Changes the agent table primary key from (id) to (id, user_id),
-- allowing different users to create agents with the same ID.
-- Only conflict with system built-in agents is prevented at app level.
--
-- Compatible with H2 (MODE=MYSQL) and PostgreSQL.
-- =============================================

-- Drop existing single-column primary key (PostgreSQL names it agent_pkey by default)
ALTER TABLE agent DROP CONSTRAINT agent_pkey;

-- Add composite primary key (id, user_id)
ALTER TABLE agent ADD PRIMARY KEY (id, user_id);
