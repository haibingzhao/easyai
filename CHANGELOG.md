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
- Tasks page deep link to a host's own result page: a `TASK_TYPE_REGISTRY` entry can declare `link(task)`, built from the row's `payload`/`result`, and the row plus detail dialog offer that navigation (`TaskLink { labelKey, path }`). Returning null renders no button, so a type whose handler writes no `result` keeps the generic row.
- Knowledge library is now a folder tree: nested folders with derived display paths, articles addressed by a stable id and placed in a folder (`GET/POST/PATCH/DELETE /api/knowledge/folders`, `POST /api/knowledge/article/move`), and the console gets a resizable tree with folder create/rename/delete, move, and upload-into-folder. A default "Uncategorized" folder is created on first write.
- Article lifecycle: drafts vs published, retrieval toggle (`ragEnabled`) with per-article index pending state, and a console editor with publish/unpublish/reindex. Only published, retrieval-enabled articles are readable through `knowledge_search`/`knowledge_read`.
- Multi-host database isolation (`owner_app` on `async_task`, `watch` and temp `project` rows): `HostIdentity` (`spring.application.name`, required and checked at startup), `LegacyOwnerAdopter` with `easyai.host.adopt-legacy-rows`, owner-scoped startup sweeps and bulk task cleanup, and `GET /api/tasks` rows expose `ownerApp`/`controllable`.
- Skills are safe when two applications share one data directory: `SkillSyncService` takes a per-owner file lock under `{rootDir}/.locks` on top of the in-process mutex, and `SkillIndexer` leases a catalog row (`next_attempt_at`) before a background reconcile so a second host skips it.

### Changed

- MCP row-addressed endpoints (`/connect`, `/disconnect`, `/tools`, `/prompts`) now default to the caller's personal bucket; shared rows must pass `?scope=group`. `McpServerDto` and `AgentDto` now expose `ownerId`.
- Hiding a chat agent now only trims the picker; an existing conversation keeps its selected agent. Changing the configured default agent only affects new chats — a conversation pins its agent on the first message.
- `@easyai/console` declarations no longer ship the internal `@/*` alias: `build:lib` post-processes `dist-lib/**/*.d.ts` (`scripts/fix-lib-alias.mjs`) and rewrites every alias specifier to a relative path. A host used to resolve none of those imports, so the whole cross-file type surface degraded to `any` — registry callbacks (`title` / `summarize` / `link`) now arrive contextually typed and need no parameter annotations at the call site.
- Knowledge API and SPI are reshaped around ids and folders: `key`/`knowledgeKey`/`source`/`relativePath` are gone (`id` + `folderId` instead), `POST /api/knowledge/save-text` takes `folderId`, unpublish is `DELETE /api/knowledge/article?id=…`, `KnowledgeFolderDto` gained `writable`, and `KnowledgeAccessPolicy` dropped `filterReadable`/`canReadEntry`/`canManageEntry` (added `canManageFolders`). `KnowledgeStore` no longer exposes `list`/`detail`/`update`/`uploadBatch`/`batchDelete`; hosts implementing the policy or the store, or building these DTOs, must recompile.
- `DELETE /api/tasks` (bulk clear) now removes only rows this application owns; a row another application is still running is left alone instead of being cancelled and deleted from here.

### Upgrade Notes

- `V36__knowledge_tree_schema.sql` is destructive: it drops `knowledge_article`/`knowledge_folder` and rebuilds them around folders. Pre-release knowledge data is wiped; there is no migration of old articles. The old EasyRAG documents keyed `easyai:{source}/{relativePath}` are **not** removed by the migration — wipe that knowledge bucket (e.g. `u_<user>_k`) before or after the upgrade, or old hits will occupy the top-K and be filtered out again, making search look empty.
- `V37__add_owner_app_isolation.sql` adds `owner_app` to `async_task`, `watch`, `project` and deliberately does **not** backfill. Unowned rows are inert (no watch fires, no task is parked, no scratch workspace is released) until claimed. Claim them by hand before starting the new build, or leave `easyai.host.adopt-legacy-rows=true` on **exactly one** application — whichever boots first otherwise claims every unowned row, including the other application's. With adoption off, every host logs the counts and the SQL at startup.
- Every application on a shared database must set an explicit, stable `spring.application.name`; the Boot default `application` is refused at startup. Rows keep the name they were written under, so renaming an application strands its rows as "foreign" (they stop firing) — reassign them with `UPDATE … SET owner_app = '<new-name>'`, and watch the startup log line that lists the owner names present in the database.
- Rolling upgrades: an old build still running next to a new one keeps writing `owner_app IS NULL` rows. They stay inert until a new-build restart runs adoption again; upgrade the whole stack together.
- The order of `V36` and `V37` relative to existing knowledge data is fixed by Flyway; `V38` adds the `knowledge_article.folder_id → knowledge_folder.id` foreign key and fails loudly if dangling rows already exist (inspect them before upgrading).
- Skill sync now writes `{rootDir}/.locks/{owner}.lock`; keep that directory writable and out of skill discovery (it is a dot directory). Two applications sharing the *same* root directory are serialized by it; separate root directories are independent by design.
- Backend and every host consuming the console `dist-lib` must be released together. If staged, upgrade the backend first: old clients lose group-row `connect`/`disconnect`/`tools`/`prompts` (404), while a new console against an old backend cannot address MCP rows (no `ownerId`) and cannot edit group Chat settings. In that degraded state the console treats MCP rows as personal, hides the group target, and keeps same-named rows read-only instead of guessing a bucket.
- `AgentDto.ownerId` is a required constructor parameter, so host code that builds `AgentDto` itself must be updated and recompiled.
