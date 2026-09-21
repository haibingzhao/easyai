# easyai-skills AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Skill plugin system + command registry + sub-agent execution: discovery, loading, registration, catalog identity, index synchronisation, and slash command handling.

## STRUCTURE
```
easyai-skills/src/main/kotlin/com/easy/easyai/skills/
├── SkillConfig.kt              # Skill configuration model (workDir, homeSkillDirs, projectDirNames, …)
├── SkillDiscovery.kt           # Filesystem scanning for skill directories (walkFileTree + skip ignored dirs)
├── SkillInfo.kt                # Skill metadata representation
├── SkillLoader.kt              # Loading skills from YAML/markdown files (line-based frontmatter parse)
├── SkillChecksums.kt           # SHA-256 fingerprints (UTF-8 pinned; HexFormat)
├── SkillPaths.kt               # Single source of truth for install_path canonicalisation (symlink-resolved)
├── SkillScopeResolver.kt       # Derives (scope, projectPath) from an install path + config
├── SkillRegistry.kt            # Central in-memory registry + lifecycle (lazy initial scan, scanLock)
├── SkillOwnership.kt           # Tenant/scope gate: fallback-to-system semantics for catalog reads
├── SkillCatalogService.kt      # Read/toggle facade for the catalog (UI + Controller-facing)
├── SkillCatalogSyncService.kt  # Disk ↔ catalog reconciliation (backfillAll, driftOf, mtime cache)
├── SkillIndexer.kt             # Catalog row ↔ skill_search index (checksum-driven drift reindex)
├── SkillRefreshService.kt      # Serialised re-scan + claim + index pass (refresh_skills backend)
├── SkillPromptSource.kt        # System-prompt visibility filter (disabled rows + RAG suppression)
├── RefreshSkillsTool.kt        # Agent-facing `refresh_skills`; the authoring path after `write`
├── RefreshSkillsToolBuilder.kt # Spring wiring
├── SkillTool.kt                # Skill-to-tool adapter for the agent loop (`load_skill`)
├── SkillToolBuilder.kt         # Spring wiring for SkillTool
├── SkillSearchTool.kt          # Agent-facing `skill_search` over the RAG index
├── SkillSearchToolBuilder.kt   # Spring wiring
├── a2a/                        # Agent-to-Agent protocol (AgentSkill, AgentSkillFactory)
├── command/
│   ├── CommandRegistry.kt      # Slash command registration + lookup
│   ├── CommandService.kt       # Command execution orchestration
│   ├── BuiltinCommandHandler.kt # Built-in command implementations
│   ├── CommandInfo.kt          # Command metadata
│   ├── CommandUtils.kt         # Argument parsing helpers
│   └── McpPromptProvider.kt    # MCP prompt template provider
├── subagent/
│   ├── SubAgentTool.kt         # Spawns child agent as tool
│   ├── SubAgentToolBuilder.kt  # Spring wiring
│   └── SubAgentExecutorStrategy.kt # Execution strategy interface
└── team/                       # Multi-agent team coordination tools
```

Related modules outside `easyai-skills`:
- `easyai-core/…/core/skill/` — `AsyncSkillCatalogStore`, `SkillCatalogEntry`, `SkillScope`, `SkillStore` interfaces (storage-agnostic)
- `easyai-repository/…/repository/skill/R2dbcAsyncSkillCatalogStore.kt` — R2DBC implementation
- `easyai-rag/…/rag/RagSkillStore.kt` — `SkillStore` implementation backed by the RAG index
- `easyai-autoconfigure/easyai-core-autoconfigure/…/SkillIndexStartupRunner.kt` — `ApplicationReadyEvent` background reconcile
- `easyai-web/…/web/controller/SkillController.kt` — HTTP facade for list/toggle

## ARCHITECTURE INVARIANTS

**Three-layer authorisation** — each layer enforces a different question, in this order:
1. **biz_id isolation** (RAG side): `skill_search` never crosses tenants; `RagSkillStore` computes `bizIds` per request.
2. **Catalog enablement filter**: rows with `enabled=false` are hidden from `SkillPromptSource.skillsForPrompt` and rejected by `SkillOwnership.checkLoad` (`Disabled`).
3. **load_skill whitelist**: the agent config's `allowedSkillNames` narrows what a specific sub-agent may serve; enforced inside `SkillTool.doExecute` **before** consulting the registry, so the error message cannot leak names outside the whitelist.

**Single write direction: disk → catalog row → index.** Nothing writes to disk from the catalog; the catalog is never authoritative for content, only for identity/enablement. The index is a projection of catalog rows whose SKILL.md checksum moved.

**`SkillOwnership.tenantOf` fallback-to-system semantics.** A request without a matching user-owned row falls back to the `system` owner (`SkillCatalogEntry.DEFAULT_USER_ID`), which is who `backfillAll` claims filesystem-discovered skills under. Consequence: `SkillRefreshService.refreshFor` must reconcile **both** the requesting owner and `DEFAULT_USER_ID` — otherwise a user editing a GLOBAL skill on disk would leave the system-owned row stale.

**Install-path canonicalisation is one function.** `SkillPaths.canonicalize` (lexical `toAbsolutePath().normalize()`, **not** symlink-resolved) is used by every write into `install_path` and every comparison against it. Do not add a second normalisation helper — the historic bug was three call sites disagreeing on whether to resolve symlinks. Lexical-only is deliberate: it is deterministic across restarts and machines and does not require the file to exist.

**Checksum-driven reindex.** `SkillIndexer.reconcileByDrift` only touches rows whose `SkillCatalogSyncService.driftOf` returns `Content(newChecksum, …)`. `mtime` is a process-local hint (`verifiedMtimes`) — correctness across restarts comes from the persisted checksum.

**RAG integration is opt-in and degradable.** The flag is `easyai.skills.rag.enabled`; when off, `SkillStore` is null and `SkillIndexer` becomes a no-op on the index side. `RagSkillStore.runCatchingRag` catches every non-cancellation `Exception`, not just `RagException`, so a JSON parse error or `IOException` in the RAG client cannot break the agent loop.

**Applied migrations are frozen (Flyway checksum).** `V6__skill_project_scoped_identity.sql` contains a `DELETE FROM skill;` that wipes every catalog row — including user `enabled=false` preferences captured by V3~V5 — and relies on the startup backfill re-claiming on-disk skills. This was acceptable only because the catalog shipped pre-release with no persisted deployment. **Once V6 has been applied to a persistent DB it must never be edited again, not even comments**: Flyway validates the file checksum against `flyway_schema_history` on every boot and a mismatch refuses startup. A production deployment upgrading from V3~V5 needs an in-place Java migration hook (derive `project_hash` from `install_path` via `SkillScopeResolver`) instead of the `DELETE`; do not retrofit it by editing V6.

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Skill lifecycle | `SkillLoader` + `SkillRegistry` | Load → Register → Activate (lazy initial scan) |
| Skill discovery | `SkillDiscovery.scanDirectory` | `walkFileTree` with `preVisitDirectory` skip |
| Catalog identity | `SkillScopeResolver` + `SkillPaths` | Scope from install path; canonical string form |
| Disk ↔ catalog sync | `SkillCatalogSyncService.backfillAll` / `driftOf` | Streaming SHA-256, bounded frontmatter read |
| Index sync | `SkillIndexer.reconcileByDrift` | Checksum-driven, disabled rows skipped |
| Authorisation gate | `SkillOwnership.checkLoad` | Three-layer filter (biz_id / enabled / whitelist) |
| Prompt visibility | `SkillPromptSource.skillsForPrompt` | Cached disabled set, RAG suppression aware |
| Re-scan after `write` | `SkillRefreshService` + `RefreshSkillsTool` | Serialized pass; reconciles user + system owners |
| Tool integration | `SkillTool` (`load_skill`) | Whitelist checked before registry lookup |
| Semantic search | `SkillSearchTool` (`skill_search`) | `AtomicReference` tenant cache |
| HTTP facade | `easyai-web/…/SkillController.kt` | `withContext(Dispatchers.IO)` on file stat |
| Startup reconcile | `easyai-autoconfigure/…/SkillIndexStartupRunner.kt` | `ApplicationReadyEvent` background pass |
| Slash commands | `command/CommandService` | /command execution flow |
| Sub-agents | `subagent/SubAgentTool` | Spawn child agent with depth limit |

## CREATING A SKILL
There is no "create a skill" tool and no HTTP endpoint for it. An authoring flow is:

1. `write` the files — absolute path into `~/.easyai/skills/<slug>/SKILL.md` for a global skill, or relative
   `<project>/.easyai/skills/<slug>/SKILL.md` (or `.agents/skills/<slug>/SKILL.md` — both roots are configured
   via `SkillConfig.homeSkillDirs` / `projectDirNames`) for a project one. One file per call; `references/*`
   and `assets/*` are separate writes, not part of the SKILL.md payload.
2. `refresh_skills` — `SkillRegistry.rescan` re-reads the directories, `SkillRefreshService.refreshFor` claims a
   catalog row for each new skill (under the requesting user, falling back to `system` for GLOBAL skills) and
   pushes it to the `skill_search` index. Until this runs the skill is a file, invisible to `load_skill` and to
   the system prompt.
3. The user ticks the skill into the agent's skill whitelist; `alwaysInclude` covers `refresh_skills` itself, not
   the skills it just discovered.

Structural rules (slug charset, length, the 500-line body cap) live in the skill-creator SKILL.md, not on the
server: `SkillLoader` only parses frontmatter, so a malformed skill shows up in `refresh_skills` as "not added"
plus a `Failed to parse SKILL.md at` warning. Do not re-add validation to the tool path — it is what forced whole
skill bodies through a single tool call and blew past output-token limits.

## CONVENTIONS
- Skills discovered via filesystem → YAML/markdown parsing → registry
- Skills converted to agent tools via `SkillTool` adapter
- Commands: registered in `CommandRegistry`, executed via `CommandService`
- Sub-agents respect `maxSubAgentDepth` from agent config
- All classes `internal` unless they are part of the cross-module public API (autoconfigure, web, rag)
- `catch (e: Exception)` must always be preceded by `catch (e: CancellationException) { throw e }` — never swallow coroutine cancellation
- Path comparisons go through `SkillPaths.canonicalize`; checksums go through `SkillChecksums.sha256Hex` (UTF-8 pinned)

## ANTI-PATTERNS
- Don't bypass `SkillRegistry` for skill activation — use the lifecycle
- Don't hardcode skill paths — rely on `SkillDiscovery`
- Don't add a create/write endpoint or tool for skills — the author writes files and `refresh_skills` publishes them
- Don't mix skill loading with tool execution — separate concerns
- Sub-agents must not exceed depth limit
- Don't reintroduce a second `normalizeDir` — use `SkillPaths`
- Don't catch bare `Exception` in suspend code without rethrowing `CancellationException` first
- Don't read whole SKILL.md files just to look at frontmatter — use the bounded reader in `SkillCatalogSyncService.declaredVersion`
