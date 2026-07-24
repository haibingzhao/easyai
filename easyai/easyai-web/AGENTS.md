# easyai-web AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
REST API layer: 14 controllers, SSE streaming, JWT security, config generation, session management.

## STRUCTURE

```
easyai-web/src/main/kotlin/com/easy/easyai/web/
├── controller/
│   ├── ChatController.kt          # Chat SSE streaming, cancel, resume, queue
│   ├── AgentController.kt         # Agent CRUD, tool listing, config validation
│   ├── SwarmController.kt         # Swarm preset CRUD, run lifecycle, task events
│   ├── SessionController.kt       # Session list/detail/delete
│   ├── ProjectController.kt       # Project CRUD, memory config
│   ├── McpServerController.kt     # MCP server config, status, reconnect
│   ├── PermissionController.kt    # Permission rules, reply to pending prompts
│   ├── MemoryController.kt        # Memory CRUD, search
│   ├── CommandController.kt       # Built-in command execution
│   ├── UserCommandController.kt   # User custom command CRUD
│   ├── ModelConfigController.kt   # Model provider config
│   ├── SkillController.kt         # Skill listing
│   ├── FileController.kt          # File upload/storage
│   └── AiConfigController.kt      # AI config generation, template validation
├── handler/
│   ├── ChatEventConverter.kt      # AgentEvent → ChatStreamEvent conversion
│   ├── CheckpointCustomEventConverter.kt  # Snapshot events
│   └── GoalStatusCustomEventConverter.kt  # Goal status events
├── model/                         # Request/response DTOs (ChatRequest, ChatStreamEvent, etc.)
├── security/
│   ├── SecurityConfig.kt          # Spring Security filter chain
│   ├── JwtAuthenticationFilter.kt # JWT token validation filter
│   ├── AuthController.kt          # Login, register, refresh, logout
│   └── AuthService.kt             # Authentication logic
└── service/
    ├── ChatStreamService.kt       # Core: AgentLoop → Flux<ServerSentEvent> bridge
    ├── SessionService.kt          # Session lifecycle, message loading
    ├── ConfigValidator.kt         # Prompt template + config validation
    ├── FileStorageService.kt      # File upload handling
    ├── GoalCommandHandler.kt      # /goal slash command
    └── configgen/                 # Agent-based config generation (DryRunAgentService)
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Chat SSE | `controller/ChatController` + `service/ChatStreamService` | POST /api/socket/chat → SSE stream |
| Event conversion | `handler/ChatEventConverter` | AgentEvent → ChatStreamEvent (20+ types) |
| Security | `security/` | JWT filter, auth endpoints, SecurityConfig |
| Swarm API | `controller/SwarmController` | Preset CRUD, run start/cancel, SSE events |
| Config generation | `service/configgen/` | LLM-powered agent config creation |

## CONVENTIONS
- WebFlux reactive: `Flux<T>`, `Mono<T>` return types
- Coroutines bridge: `asFlux()` for Flow → Flux (kotlinx-coroutines-reactor)
- SSE format: `ServerSentEvent.builder<T>().event(type).data(payload).build()`
- Jackson polymorphic: `@JsonTypeInfo` + `@JsonSubTypes` on ChatStreamEvent
- `@JsonInclude(JsonInclude.Include.NON_NULL)` on optional fields
- URL prefix: all endpoints under `/api/`

## ANTI-PATTERNS
- Don't block on Flux — use `asFlux()` from Flow
- Don't bypass `SessionManager` for session lifecycle
- Errors: return `ChatStreamEvent.Error` via `onErrorResume`, never throw
- Don't add business logic to controllers — delegate to services
