-- V1: Initial schema for EasyAI
-- Translated from Tables.kt (Exposed DSL) to portable DDL.
-- Compatible with H2 (MODE=MYSQL) and PostgreSQL.
-- All statements use IF NOT EXISTS for safe re-execution on existing databases.

-- =============================================
-- Users & Authentication
-- =============================================

CREATE TABLE IF NOT EXISTS app_user (
    id VARCHAR(255) PRIMARY KEY,
    username VARCHAR(128) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    password_hash VARCHAR(256) NOT NULL,
    avatar VARCHAR(64) NOT NULL DEFAULT 'avatar-1',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_app_user_username ON app_user (username);

CREATE TABLE IF NOT EXISTS refresh_token (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    token_hash VARCHAR(256) NOT NULL,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_refresh_token_user_id ON refresh_token (user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_token_token_hash ON refresh_token (token_hash);

-- =============================================
-- Agent
-- =============================================

CREATE TABLE IF NOT EXISTS agent (
    id VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    agent_type VARCHAR(32) NOT NULL DEFAULT 'PRIMARY',
    agent_context VARCHAR(32) NOT NULL DEFAULT 'CHAT',
    description TEXT,
    prompt_template TEXT,
    custom_instructions TEXT,
    max_iterations INT NOT NULL DEFAULT 50,
    max_subagent_depth INT NOT NULL DEFAULT 1,
    color VARCHAR(32),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    instructions_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    input_schema TEXT,
    output_schema TEXT,
    user_id VARCHAR(255) NOT NULL DEFAULT 'system',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT agent_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS agent_tool (
    id VARCHAR(255) PRIMARY KEY,
    agent_id VARCHAR(255) NOT NULL,
    target_type VARCHAR(16) NOT NULL DEFAULT 'TOOL',
    target_name VARCHAR(128) NOT NULL DEFAULT '',
    metadata TEXT
);

CREATE INDEX IF NOT EXISTS idx_agent_tool_agent_id ON agent_tool (agent_id);

-- =============================================
-- User Commands
-- =============================================

CREATE TABLE IF NOT EXISTS user_command (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    aliases TEXT,
    template TEXT,
    hints TEXT,
    user_id VARCHAR(255) NOT NULL DEFAULT 'system',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_command_user_id ON user_command (user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_command_user_name ON user_command (user_id, name);

-- =============================================
-- Project & Session & Message
-- =============================================

CREATE TABLE IF NOT EXISTS project (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    path VARCHAR(512) NOT NULL,
    description TEXT,
    user_id VARCHAR(255) NOT NULL DEFAULT 'system',
    memory_auto_generation BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_project_path_user ON project (path, user_id);

CREATE TABLE IF NOT EXISTS session (
    id VARCHAR(255) PRIMARY KEY,
    project_id VARCHAR(255),
    title VARCHAR(255) NOT NULL DEFAULT 'New Chat',
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    pending_permission TEXT,
    user_id VARCHAR(255) NOT NULL DEFAULT 'system',
    swarm_run_id VARCHAR(255),
    swarm_task_id VARCHAR(255),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    content_updated_at BIGINT NOT NULL DEFAULT 0,
    end_reason VARCHAR(32),
    goal_json TEXT
);

CREATE TABLE IF NOT EXISTS message (
    id VARCHAR(255) PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255),
    config_id VARCHAR(255),
    model_id VARCHAR(255),
    role VARCHAR(32) NOT NULL,
    content_blocks TEXT,
    metadata TEXT,
    input_token_count INT,
    output_token_count INT,
    cache_read_token_count INT,
    cache_write_token_count INT,
    stop_reason VARCHAR(32),
    compacted_at BIGINT,
    duration_ms BIGINT,
    parent_message_id VARCHAR(255),
    parent_tool_call_id VARCHAR(255),
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_message_session_created ON message (session_id, created_at);

-- =============================================
-- Model Configuration
-- =============================================

CREATE TABLE IF NOT EXISTS model_provider_config (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    protocol VARCHAR(64) NOT NULL,
    is_custom BOOLEAN NOT NULL,
    base_url VARCHAR(512),
    api_key TEXT,
    model_id VARCHAR(255) NOT NULL,
    model_name VARCHAR(255),
    is_custom_model BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL,
    options TEXT,
    capabilities TEXT,
    timeout_seconds BIGINT NOT NULL DEFAULT 600,
    user_id VARCHAR(255) NOT NULL DEFAULT 'system',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- =============================================
-- Todo
-- =============================================

CREATE TABLE IF NOT EXISTS todo (
    id VARCHAR(255) PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    agent_run_id VARCHAR(255),
    content TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    priority VARCHAR(16) NOT NULL,
    position INT NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_todo_session_agent ON todo (session_id, agent_run_id);

-- =============================================
-- Permission
-- =============================================

CREATE TABLE IF NOT EXISTS permission_rule (
    id VARCHAR(255) PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    permission VARCHAR(255) NOT NULL,
    pattern VARCHAR(512) NOT NULL,
    action VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_permission_rule_project ON permission_rule (project_id);

-- =============================================
-- MCP Server Config
-- =============================================

CREATE TABLE IF NOT EXISTS mcp_server_config (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(16) NOT NULL,
    command TEXT,
    env TEXT,
    url VARCHAR(512),
    headers TEXT,
    cwd VARCHAR(512),
    timeout_seconds BIGINT NOT NULL DEFAULT 120,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    user_id VARCHAR(255) NOT NULL DEFAULT 'system',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_mcp_server_user_name ON mcp_server_config (user_id, name);

-- =============================================
-- Swarm
-- =============================================

CREATE TABLE IF NOT EXISTS swarm_run (
    id VARCHAR(255) PRIMARY KEY,
    preset_name VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    agents TEXT NOT NULL,
    user_vars TEXT NOT NULL,
    total_input_tokens BIGINT NOT NULL DEFAULT 0,
    total_output_tokens BIGINT NOT NULL DEFAULT 0,
    total_cache_read_tokens BIGINT NOT NULL DEFAULT 0,
    total_cache_write_tokens BIGINT NOT NULL DEFAULT 0,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    error TEXT,
    user_id VARCHAR(255) NOT NULL DEFAULT 'system',
    created_at BIGINT NOT NULL,
    started_at BIGINT,
    completed_at BIGINT
);

CREATE INDEX IF NOT EXISTS idx_swarm_run_created ON swarm_run (created_at);
CREATE INDEX IF NOT EXISTS idx_swarm_run_user ON swarm_run (user_id);

CREATE TABLE IF NOT EXISTS swarm_task (
    id VARCHAR(255) PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL,
    task_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary TEXT,
    error TEXT,
    worker_iterations INT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cache_read_tokens BIGINT NOT NULL DEFAULT 0,
    cache_write_tokens BIGINT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    started_at BIGINT,
    completed_at BIGINT
);

CREATE INDEX IF NOT EXISTS idx_swarm_task_run ON swarm_task (run_id);

CREATE TABLE IF NOT EXISTS swarm_deliberation_history (
    id VARCHAR(255) PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL,
    task_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255) NOT NULL,
    round INT NOT NULL,
    response TEXT NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cache_read_tokens BIGINT NOT NULL DEFAULT 0,
    cache_write_tokens BIGINT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    opening_prompt TEXT,
    round_prompts TEXT,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_deliberation_history_run_task ON swarm_deliberation_history (run_id, task_id);

CREATE TABLE IF NOT EXISTS swarm_deliberation_verdict (
    id VARCHAR(255) PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL,
    task_id VARCHAR(255) NOT NULL,
    verdict_prompt TEXT NOT NULL,
    verdict_response TEXT NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_deliberation_verdict_run_task ON swarm_deliberation_verdict (run_id, task_id);

CREATE TABLE IF NOT EXISTS swarm_escalation_history (
    id VARCHAR(255) PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL,
    task_id VARCHAR(255) NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    round INT NOT NULL,
    reason TEXT NOT NULL,
    resolution TEXT,
    reassigned_to VARCHAR(255),
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_escalation_history_run_task ON swarm_escalation_history (run_id, task_id);

CREATE TABLE IF NOT EXISTS swarm_team_member_execution (
    id VARCHAR(255) PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL,
    task_id VARCHAR(255) NOT NULL,
    member_id VARCHAR(255) NOT NULL,
    round INT NOT NULL,
    assignment TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary TEXT,
    escalation_reason TEXT,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_team_member_exec_run_task ON swarm_team_member_execution (run_id, task_id);

CREATE TABLE IF NOT EXISTS swarm_team_round_record (
    id VARCHAR(255) PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL,
    task_id VARCHAR(255) NOT NULL,
    round INT NOT NULL,
    leader_analysis TEXT NOT NULL,
    delegated_members TEXT NOT NULL,
    completed_members TEXT NOT NULL,
    escalations TEXT NOT NULL,
    leader_prompt TEXT,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_team_round_record_run_task ON swarm_team_round_record (run_id, task_id);

CREATE TABLE IF NOT EXISTS swarm_preset (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    agents_json TEXT NOT NULL,
    tasks_json TEXT NOT NULL,
    variables_json TEXT NOT NULL,
    language VARCHAR(16) NOT NULL DEFAULT '',
    user_id VARCHAR(255) NOT NULL DEFAULT 'system',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_swarm_preset_user_name ON swarm_preset (user_id, name);
