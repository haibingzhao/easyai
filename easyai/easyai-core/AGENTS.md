# easyai-core AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Core agent runtime: ReAct loop, tool execution engine, event system, permission control, memory, goal tracking, session management.

## STRUCTURE

```
easyai-core/src/main/kotlin/com/easy/easyai/core/
├── agent/       # AgentLoop, AgentLoopRunner, AgentService, ChatSession, SessionManager,
│                #   AgentContext, AgentHook, AgentCompletionCheck, SubAgentContextResolver,
│                #   PendingMessageQueue, PendingToolCallExecutor, LlmErrorClassifier
├── tool/        # ToolDefinition, ToolBuilder, ToolExecutionEngine, ToolFactory, ToolMetadata
├── event/       # AgentEvent (sealed hierarchy), MessageListener
├── permission/  # PermissionService, PermissionEvaluator, PermissionRule, SafeCommandDetector
├── memory/      # MemoryStore, FileMemoryStore, MemoryFlushAgent, MemoryLoader, MemoryEntry
├── goal/        # GoalState, GoalCompletionCheck, GoalStatusNotifier, GoalStore
├── command/     # UserCommandDefinition, AsyncUserCommandStore
├── model/       # EasyAiMessage, ProjectInfo, TodoInfo, ToolCallStatus
└── message/     # MessageConverter (EasyAI ↔ Spring AI bridge)
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Agent loop | `agent/AgentLoop.kt` + `AgentLoopRunner.kt` | ReAct: call→extract→execute→repeat, maxIterations |
| Session lifecycle | `agent/ChatSession.kt` + `SessionManager.kt` | Session state, message history, model switching |
| Tool execution | `tool/ToolExecutionEngine.kt` | Sequential/parallel dispatch, error handling |
| Tool definition | `tool/ToolDefinition.kt` | Interface: name, description, doExecute() |
| Events | `event/AgentEvent.kt` | Sealed: MessageStart/End, ToolExecution*, Error, etc. |
| Permission | `permission/PermissionService.kt` | Rule evaluation, user prompt flow |
| Memory | `memory/FileMemoryStore.kt` | File-based CRUD + search |
| Goal tracking | `goal/GoalState.kt` + `GoalCompletionCheck.kt` | Auto-pause on completion |
| Message conversion | `message/MessageConverter.kt` | EasyAI ↔ Spring AI message types |

## CONVENTIONS
- `suspend fun` + `withContext(Dispatchers.IO)` for all I/O
- Data classes for state → `copy()` not builders
- Sealed interfaces for event/message/stop-reason types
- `internal` visibility unless public API
- Tests: `@Nested` classes, backtick naming, MockK + kotlin-test

## ANTI-PATTERNS
- Never `ToolCallingChatModel` — conflicts with custom ReAct loop
- Never `stream()` for tool calls — use `call()`
- Never block — all LLM/network must be suspend
- Never extension functions — `@JvmStatic` companion methods
- No builder pattern — use `copy()` / withers
