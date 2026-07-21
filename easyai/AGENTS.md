# AGENTS.md - EasyAI LLM Harness Framework

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Kotlin/Spring Boot 4.1 LLM Harness: ReAct agent loop, 20+ built-in tools, skill/command plugin system, multi-agent swarm orchestration, SSE streaming, async R2DBC persistence, JWT auth, git-based snapshot/revert.

## MODULE STRUCTURE

```
easyai/
├── easyai-core/           # Agent loop, tool engine, events, permission, memory, goal, session
├── easyai-tools/          # 20+ tools: file(read/write/edit), search(grep/glob/ls), shell(bash),
│                          #   web(fetch/search), mcp, memory, goal, todo, question, calc
├── easyai-agent/          # Agent abstraction layer (2 sub-modules)
│   ├── easyai-agent-core/ #   AgentDto, ToolInfo, ToolRegistry
│   └── easyai-agent-coding/ # Coding-specific agent configuration
├── easyai-swarm/          # Multi-agent orchestration: DAG scheduling, SINGLE/TEAM/DELIBERATION tasks
├── easyai-skills/         # Skill plugin: discovery→load→register, commands, subagent execution
├── easyai-snapshot/       # Git checkpoint/revert: SnapshotService, RevertService, event listener
├── easyai-compaction/     # Context compaction: token-triggered, summary/LLM strategies
├── easyai-auth/           # JWT token provider, user store, refresh token rotation
├── easyai-repository/     # Exposed R2DBC: 18 tables, async suspend/Flow, DB migration
├── easyai-api/            # Model config service, ChatModelFactory, provider config
├── easyai-observability/  # @Tracked AOP, MDC propagation, OTel+Micrometer handlers
├── easyai-web/            # REST controllers (14), SSE streaming, security (JWT filter), services
├── easyai-common/         # 4 sub-modules: bom, core (marker interfaces, thinking), textio (Jinja), util
├── easyai-autoconfigure/  # 8 auto-config sub-modules (core, web, observability, openai, anthropic, r2dbc, compaction, swarm)
└── easyai-starters/       # Starter POM aggregation
```

## KEY ARCHITECTURE DECISIONS

**Agent Loop** (`easyai-core/agent/`): Manual ReAct (call→extract toolCalls→execute→repeat). `AgentLoop` + `AgentLoopRunner` orchestrate iterations. Uses `ChatModel.call()` not `stream()`. Supports sub-agents, hooks, completion checks, pending message queues.

**Tool System** (`easyai-core/tool/` + `easyai-tools/`): `ToolDefinition` interface with `ToolBuilder` pattern for Spring wiring. `ToolExecutionEngine` handles sequential/parallel execution. Each tool category has its own builder (e.g., `FileToolBuilders`, `ShellToolBuilders`, `WebToolBuilders`).

**Swarm** (`easyai-swarm/`): DAG-based multi-agent orchestration. `SwarmRuntime` manages run lifecycle. Task types: SINGLE (one agent), TEAM (parallel workers with leader), DELIBERATION (multi-round debate + judge). `SwarmEventBridge` emits progress events.

**Permission** (`easyai-core/permission/`): `PermissionService` evaluates tool actions against `PermissionRule` patterns. Supports auto-approve, ask-user (SSE prompt), deny. `SafeCommandDetector` for bash command risk analysis.

**Memory** (`easyai-core/memory/`): `FileMemoryStore` persists memories as files. `MemoryFlushAgent` uses LLM to extract memories from conversations. Scoped to project.

**Snapshot** (`easyai-snapshot/`): `GitSnapshotService` creates git checkpoints. `RevertService` supports file-level selective revert. `SnapshotEventListener` auto-checkpoints on tool executions.

**Repository** (`easyai-repository/`): Exposed R2DBC, never JDBC. All ops in `asyncTransaction { }`. 18 tables. `DatabaseMigration` handles schema evolution. `UserScope` provides multi-user data isolation.

**Skills & Commands** (`easyai-skills/`): Skill discovery via filesystem → YAML/markdown → `SkillRegistry`. `CommandRegistry` + `CommandService` for slash commands. `SubAgentTool` for spawning child agents.

## CRITICAL PATTERNS

### Tool Definition
```kotlin
class MyTool : ToolDefinition {
    override val name = "my_tool"
    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String, args: Map<String, Any?>,
        scope: CoroutineScope, onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult { ... }
}
// Wired via ToolBuilder:
@Component class MyToolBuilder : ToolBuilder { ... }
```

### SSE Event Flow
```
AgentLoop → AgentEvent → ChatStreamService → ChatEventConverter → Flux<ServerSentEvent>
```

### Web Controllers (14)
ChatController, AgentController, SwarmController, SessionController, ProjectController,
ModelConfigController, McpServerController, PermissionController, MemoryController,
CommandController, UserCommandController, SkillController, FileController, AiConfigController

## TESTING

- **Frameworks**: JUnit 5, MockK 1.13.x, SpringMockK 4.0.2, kotlin-test, kotlinx-coroutines-test
- **Naming**: `@Nested` classes + backtick method names: ``fun `test describes behavior`()``. No `@DisplayName`.
- **Locale**: Forced `en_US` via surefire `-Duser.language=en -Duser.country=US`
- **Mock setup**: `mockk<ChatModel>()`, `every { } returnsMany responses`, `runTest` for coroutine tests

## CONVENTIONS

- No builder pattern → `copy()` on data classes
- No extension functions (not visible from Java) → `@JvmStatic` companion methods
- `@JvmOverloads` for Java interop with defaults
- All classes `internal` unless public API; `@ApiStatus.Internal` for technical-public
- Kotlin compiler: spring all-open + `-Xjvm-default=all` + `-Xlambdas=indy`
- Virtual threads: `spring.threads.virtual.enabled=true`
- No fully qualified class names — always use `import` statements
- Log placeholders: `logger.info("{} {}", a, b)` — never string concatenation

## ANTI-PATTERNS

- `ToolCallingChatModel` — conflicts with custom ReAct loop
- `println()` for diagnostics — SLF4J only
- `.block()`, `.blockFirst()`, `runBlocking { }` in repository
- `transaction { }` (sync) in repository — use `asyncTransaction { }`
- Spring AI `stream()` for tool calls — use `call()`
- Extension functions — not visible from Java
