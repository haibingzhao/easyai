-- =============================================
-- Team Agent Tables
--
-- Independent persistence for Team Agent (AgentType.TEAM) coordination.
-- NOT shared with Swarm tables: Swarm is keyed by (run_id, task_id) in a DAG
-- context; Team Agent is keyed by team_session_id in a Chat context.
--
-- Compatible with H2 (MODE=MYSQL) and PostgreSQL.
-- =============================================

CREATE TABLE IF NOT EXISTS team_member_execution (
    id                  VARCHAR(64) PRIMARY KEY,
    team_session_id     VARCHAR(64) NOT NULL,
    member_id           VARCHAR(128) NOT NULL,
    round               INT NOT NULL DEFAULT 1,
    assignment          TEXT NOT NULL,
    status              VARCHAR(20) NOT NULL,
    summary             TEXT,
    escalation_reason   TEXT,
    member_session_id   VARCHAR(64),
    tool_call_id        VARCHAR(128),
    input_tokens        BIGINT DEFAULT 0,
    output_tokens       BIGINT DEFAULT 0,
    started_at          BIGINT,
    completed_at        BIGINT
);

CREATE INDEX IF NOT EXISTS idx_team_exec_session ON team_member_execution(team_session_id);

CREATE TABLE IF NOT EXISTS team_round_record (
    id                  VARCHAR(64) PRIMARY KEY,
    team_session_id     VARCHAR(64) NOT NULL,
    round               INT NOT NULL,
    delegated_members   TEXT,
    completed_members   TEXT,
    blocked_members     TEXT,
    resumed_members     TEXT,
    created_at          BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_team_round_session ON team_round_record(team_session_id);

-- Add member_session_id to swarm_team_member_execution (shared model alignment)
ALTER TABLE swarm_team_member_execution ADD COLUMN IF NOT EXISTS member_session_id VARCHAR(64);
