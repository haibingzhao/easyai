# easyai-repository AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Async data persistence: Exposed R2DBC, 18 tables, full suspend/Flow, zero blocking. Multi-user isolation via UserScope.

## STRUCTURE

```
easyai-repository/src/main/kotlin/com/easy/easyai/repository/
├── database/
│   ├── Tables.kt                 # 18 Exposed table definitions
│   ├── DatabaseMigration.kt      # Schema evolution (DDL ALTER/CREATE)
│   └── UserScope.kt              # Multi-user data isolation helper
├── session/
│   ├── AsyncSessionStore.kt      # Interface: session + message CRUD
│   ├── R2dbcAsyncSessionStore.kt # Implementation (992 lines)
│   ├── DatabaseSessionManager.kt # SessionManager impl with persistence
│   ├── SessionAgentFactory.kt    # Creates Agent from session config
│   ├── SessionToolResolver.kt    # Resolves tools for session
│   └── R2dbcCompactionListener.kt # Persists compaction events
├── agent/
│   ├── R2dbcAgentStore.kt        # Agent CRUD + seed data
│   └── AgentSeedData.kt          # Default agent initialization
├── swarm/
│   ├── R2dbcSwarmPresetStore.kt  # Swarm preset CRUD
│   └── R2dbcSwarmRunStore.kt     # Run/task state persistence
├── config/
│   └── R2dbcModelConfigStore.kt  # Model provider config CRUD
├── project/
│   └── R2dbcAsyncProjectStore.kt # Project CRUD
├── auth/
│   └── R2dbcRefreshTokenStore.kt # Refresh token persistence
├── command/
│   └── R2dbcAsyncUserCommandStore.kt # User command CRUD
├── goal/
│   └── SqlGoalStore.kt           # Goal state persistence
├── mcp/
│   └── R2dbcMcpServerStore.kt    # MCP server config persistence
├── permission/
│   └── R2dbcAsyncPermissionRuleStore.kt # Permission rule CRUD
├── todo/
│   ├── R2dbcAsyncTodoStore.kt    # Todo item persistence
│   ├── TodoCompletionCheck.kt    # AgentCompletionCheck for todos
│   └── TodoWriteToolBuilder.kt   # Session-scoped todo tool
└── user/
    └── R2dbcUserStore.kt         # User account CRUD
```

## TABLES (18)

| Table | Purpose |
|-------|---------|
| `app_user` | User accounts (BCrypt password hash) |
| `refresh_token` | JWT refresh tokens (SHA-256 hash) |
| `agent` | Agent definitions (type, prompt, tools, config) |
| `agent_tool` | Agent-tool associations |
| `user_command` | Custom slash commands per user |
| `project` | Workspace metadata (path, memory config) |
| `session` | Chat sessions (status, goal, swarm ref) |
| `message` | Messages (role, content blocks, tokens, compaction) |
| `model_provider_config` | LLM provider configs |
| `todo` | Todo items per session |
| `permission_rule` | Tool permission rules |
| `mcp_server_config` | MCP server configurations |
| `swarm_run` | Swarm execution runs |
| `swarm_task` | Swarm task instances |
| `swarm_deliberation_history` | Deliberation round messages |
| `swarm_deliberation_verdict` | Judge verdicts |
| `swarm_escalation_history` | Task escalations |
| `swarm_team_member_execution` | Team worker executions |

## ASYNC RULES

**Core stack**: Exposed 1.2.0 (R2DBC), H2/PostgreSQL drivers, Project Reactor + kotlinx-coroutines-reactor.

**Interface pattern**:
```kotlin
interface AsyncXxxStore {
    suspend fun save(entity: Entity)
    suspend fun findById(id: String): Entity?
    fun findAll(): Flow<Entity>
    suspend fun delete(id: String)
}
```

**Correct**: `suspend fun` + `asyncTransaction { }`, Flow for multi-row, return business models.
**Forbidden**: `transaction { }` (sync), `.block()`, `.blockFirst()`, `runBlocking { }`, expose `ResultRow`.

## CONVENTIONS
- `UserScope` wraps userId for data isolation in queries
- `DatabaseMigration` runs DDL on startup — additive only (ALTER ADD, CREATE IF NOT EXISTS)
- Return domain models, never `ResultRow`
- All stores are Spring `@Component` beans wired via autoconfigure

## ANTI-PATTERNS
- Never use sync `transaction { }` — only `asyncTransaction { }`
- Never expose `ResultRow` outside repository layer
- Never use JDBC — R2DBC only
- Never skip `UserScope` filtering in multi-user queries
