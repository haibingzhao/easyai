# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- ReAct Agent Loop with 3-phase tool execution and context overflow self-healing
- DAG-based Swarm orchestration (SINGLE / TEAM / DELIBERATION task types)
- AI Deliberation Debates with neutral judge and autonomous convergence
- Leader-Member reactive teams with structured decision protocol
- AI Config Generator — natural language to complete Agent/Workflow configs
- Incremental context compaction with usage-aware token estimation
- Defense-in-depth permission system with shell command safety classification
- Shadow Git snapshots with author attribution and file-level revert
- Async MCP integration (Stdio + StreamableHTTP transports)
- Channel-based SSE event streaming with 15+ typed events
- JWT authentication with RS256 and refresh token rotation
- Desktop app (Electron + bundled JRE + embedded H2)
- Web console (React 18 + TypeScript + TailwindCSS 4)
- File-based memory system with LLM-powered flush
- Observability module (Micrometer Tracing + OpenTelemetry)
- Chat surface preferences per owner — the default agent for a fresh chat and the agents hidden from the composer picker, stored per personal or shared group bucket (`GET`/`PUT /api/chat-preferences`, `chat_preference` table, Settings → Chat Agents). Hidden ids must be enabled chat agents and at least one chat agent stays visible.

### Changed

- MCP row-addressed endpoints (`/connect`, `/disconnect`, `/tools`, `/prompts`) now default to the caller's personal bucket; shared rows must pass `?scope=group`. `McpServerDto` and `AgentDto` now expose `ownerId`.
- Hiding a chat agent now only trims the picker; an existing conversation keeps its selected agent. Changing the configured default agent only affects new chats — a conversation pins its agent on the first message.

### Upgrade Notes

- Backend and every host consuming the console `dist-lib` must be released together. If staged, upgrade the backend first: old clients lose group-row `connect`/`disconnect`/`tools`/`prompts` (404), while a new console against an old backend cannot address MCP rows (no `ownerId`) and cannot edit group Chat settings. In that degraded state the console treats MCP rows as personal, hides the group target, and keeps same-named rows read-only instead of guessing a bucket.
- `AgentDto.ownerId` is a required constructor parameter, so host code that builds `AgentDto` itself must be updated and recompiled.
