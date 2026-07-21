# AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## WORKSPACE LAYOUT

Four independent Maven/npm projects side-by-side. No root build file, no unified workspace config.

| Project | Path | Tech | Purpose |
|---------|------|------|---------|
| easyai | `easyai/` | Kotlin 2.3 / Spring Boot 4.1 + Maven | LLM harness framework (agent loop, tools, skills, swarm, SSE, persistence) |
| easyai-console | `easyai-console/` | React 18 + TypeScript 5.9 + Vite 6 + TailwindCSS 4 | Frontend console (chat, agents, swarm workflow, MCP, memories) |
| easyai-apps | `easyai-apps/` | Kotlin/Spring Boot + Maven | Runnable apps: `easyai-web-server` (dev server) + `easyai-desktop-server` (desktop backend) |
| easyai-desktop | `easyai-desktop/` | Electron 33 + TypeScript + esbuild | Desktop client shell: spawns backend fat jar with bundled JRE |

The Maven projects use version `2026.0.1-SNAPSHOT`.

## COMMANDS

### Backend (easyai/)
```bash
./mvnw clean install                       # Build all 15 modules
./mvnw test                                # Tests (en_US locale forced in surefire)
./mvnw test -pl easyai-core                # Run single module tests
./mvnw test -pl easyai-core -Dtest="AgentLoopTest"  # Run single test class
```

### Apps (easyai-apps/)
```bash
./mvnw clean install                       # Separate build
./mvnw spring-boot:run -pl easyai-web-server  # Run web server on :8080
```

### Frontend (easyai-console/)
```bash
npm run lint                               # ESLint check
tsc -b                                     # Type check
```

**NEVER run** `npm run dev`, `npm run build`, or `npm run preview` in agent workflows.

### Desktop client (easyai-desktop/)
```bash
scripts/build-backend.sh                   # console dist -> fat jar -> backend/easyai-desktop-server.jar
scripts/fetch-jre.sh                       # bundle Temurin JRE 21 into backend/jre
npm run desktop:dev                        # launch Electron; spawns jar on free port, H2 file DB
npm run desktop:build                      # full pipeline + electron-builder (dmg/nsis/AppImage)
```
Dev escape hatches: `EASYAI_BACKEND_URL` (attach to external backend), `EASYAI_JAR_PATH` (custom jar). User config: `{userData}/desktop-config.json` (`dbMode: h2|postgres`, `h2Dir`, `workDir`, `jvmArgs`).

## CRITICAL ANTI-PATTERNS

These are project-specific traps that differ from framework defaults:

- **No `println()`** — SLF4J logger only (CLI interactive prompts excepted)
- **No builder pattern** — use `copy()` / wither methods on data classes
- **No extension functions** — not visible from Java; use `@JvmStatic` companion methods
- **No `ToolCallingChatModel`** — conflicts with custom ReAct loop
- **No Spring AI `stream()` for tool calls** — use `call()`
- **No `transaction { }`** in repository — only `asyncTransaction { }` (Exposed R2DBC)
- **No `.block()`, `.blockFirst()`, `runBlocking { }`** in repository layer
- **No `any` types** in frontend TypeScript
- **No inline/dynamic imports** for types in frontend — standard top-level imports only
- **No frontend tests** — easyai-console has zero test coverage

## KEY ARCHITECTURE

- **Agent loop**: Manual ReAct (`call` → extract toolCalls → execute → repeat) in `easyai-core`. Not using Spring AI's built-in tool calling.
- **Event system**: `AgentEvent` sealed hierarchy via Kotlin Channels, consumed as `Flow<T>`.
- **Tool system**: `ToolDefinition` interface + `ToolBuilder` pattern. `doExecute()` is `suspend fun`. Tools organized by category (file, search, shell, web, mcp, memory, goal, todo, question, calc).
- **Swarm multi-agent**: DAG-based task orchestration in `easyai-swarm`. Supports SINGLE tasks, TEAM tasks (parallel workers), and DELIBERATION (multi-round debate with judge).
- **Permission system**: Rule-based tool permission evaluation in `easyai-core/permission/`. Auto-approve, ask-user, or deny per tool/action pattern.
- **Memory system**: File-based memory store with search, scoped to project. LLM-powered memory flush.
- **Snapshot/Revert**: Git-based checkpoint system in `easyai-snapshot`. Automatic checkpoints on tool executions, file-level revert.
- **Repository**: Exposed R2DBC (async), never JDBC. 18 tables covering users, agents, sessions, messages, swarm, MCP, permissions, todos.
- **SSE bridge**: `ChatStreamService` bridges Kotlin Flow → Reactor Flux via kotlinx-coroutines-reactor.
- **Auth**: JWT-based authentication with refresh token rotation in `easyai-auth`.
- **Frontend SSE**: `ChatService` → `useChatStore.handleEvent()` → UI update.
- **Vite proxy**: `/api` → `http://localhost:8080` (backend must run separately).

## CONVENTIONS (non-obvious)

- All classes `internal` unless public API; `@ApiStatus.Internal` for technical-public
- Kotlin compiler: spring all-open plugin + `-Xjvm-default=all` + `-Xlambdas=indy`
- Netty BOM override: `4.1.132.Final` (CVE fix across all modules)
- Virtual threads: `spring.threads.virtual.enabled=true`
- Test naming: `@Nested` classes + backtick names, no `@DisplayName`. MockK for Kotlin, Mockito for Java.
- No fully qualified class names — always use imports
- Log statements: use placeholders `logger.info("{} {}", a, b)`, never string concatenation

## INSTRUCTION FILES

Detailed guidance lives in sub-AGENTS.md files. Consult these when working in specific modules:

- `easyai/coding-style.md` — Style rules (naming, formatting, Java interop)
- `easyai/AGENTS.md` — Backend architecture, patterns, testing
- `easyai/easyai-*/AGENTS.md` — Module-specific guidance (core, web, repository, tools, skills, compaction, etc.)
- `easyai-console/AGENTS.md` — Frontend conventions, SSE flow, endpoints
- `easyai-console/src/components/chat/AGENTS.md` — Chat component details
- `easyai-console/src/components/chat/tools/AGENTS.md` — Tool message renderers
- `easyai-console/src/components/artifacts/AGENTS.md` — Artifact viewers (MIME routing)
- `easyai-apps/AGENTS.md` — Runnable apps (web server, desktop server)

## BUILD/CI NOTES

- **No CI/CD**: no `.github/workflows/`, no Makefile
- **Maven Wrapper**: use `./mvnw` in `easyai/` and `easyai-apps/`
- **Netty BOM override**: `4.1.132.Final` (CVE fix) in `easyai/pom.xml`
- **xlsx from CDN**: `https://cdn.sheetjs.com/xlsx-0.20.3/xlsx-0.20.3.tgz` (not npm registry)
