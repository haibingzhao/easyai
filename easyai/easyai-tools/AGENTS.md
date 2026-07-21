# easyai-tools AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
20+ built-in tools organized by category: file operations, search, shell, web, MCP, memory, goal, todo, question, calc.

## STRUCTURE

```
easyai-tools/src/main/kotlin/com/easy/easyai/tools/
├── file/            # ReadTool, WriteTool, EditTool + FileToolBuilders
├── search/          # GrepTool, GlobTool, LsTool + SearchToolBuilders
├── shell/           # BashTool, ShellEnvProbe + ShellToolBuilders
├── web/             # WebFetchTool, WebSearchTool, HtmlConverter + WebToolBuilders
├── mcp/             # McpClientManager, McpToolDefinition, McpToolProvider, McpServerConfig
├── memory/          # MemoryWriteTool, MemoryReadTool, MemorySearchTool, MemoryListTool + MemoryToolBuilders
├── goal/            # GoalTool + GoalToolBuilder
├── todo/            # TodoWriteTool, TodoManager
├── question/        # AskQuestionTool + QuestionToolBuilders
├── calc/            # ScriptCalcTool, GroovySandbox + ScriptCalcToolBuilder
├── PathUtils.kt     # Path resolution helpers
├── ProcessUtils.kt  # Process execution with timeout
└── SpringToolFactory.kt  # Spring component scanning for tool builders
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| File tools | `file/` | Read (line range), Write (create/overwrite), Edit (string replace) |
| Search tools | `search/` | Grep (regex), Glob (pattern), Ls (directory listing) |
| Shell execution | `shell/BashTool` | Command execution with timeout, env probe |
| Web tools | `web/` | Fetch (URL→markdown), Search (via MCP), HtmlConverter |
| MCP integration | `mcp/McpClientManager` | stdio/SSE transport, tool discovery, lifecycle |
| Memory tools | `memory/` | CRUD operations on project memory store |
| Tool builders | `*ToolBuilders.kt` | Spring @Component wiring per category |

## CONVENTIONS
- All tools implement `ToolDefinition` interface (from easyai-core)
- Each category has a `*ToolBuilders.kt` for Spring DI wiring
- `doExecute()` is `suspend fun` — use `withContext(Dispatchers.IO)` for I/O
- Use `onUpdate` callback for streaming progress to frontend
- `ToolResult(content = listOf(TextContent("result")))` for success
- `ToolResult(error = "message")` for failures
- MCP tools: `McpToolDefinition` wraps remote MCP server tools

## ANTI-PATTERNS
- Don't use `println` for output — use `ToolResult` content
- Don't block in `doExecute` — use `withContext(Dispatchers.IO)`
- Bash tool must have timeout and path restrictions
- Don't skip `onUpdate` calls — required for streaming progress
- MCP stdio: logs must go to stderr, never stdout (breaks JSON-RPC)
