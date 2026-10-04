# easyai-skills AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Skill plugin system + command registry + sub-agent execution: owner-granular loading, catalog
identity, package/DB/RAG synchronisation, and slash command handling.

Skills are **USER granularity only**. Every owner (a real user id, or the shared `system` layer)
owns one root directory `{easyai.skills.root-dir}/{owner}` and each skill is one subdirectory
`{root}/{name}` containing `SKILL.md`. There is no GLOBAL/PROJECT scope, no workDir/home/project
path scanning, and no `project_hash` any more.

## STRUCTURE
```
easyai-skills/src/main/kotlin/com/easy/easyai/skills/
├── SkillConfig.kt              # rootDir, packageMaxBytes, enabled, injectIntoSystemPrompt
├── SkillDiscovery.kt           # discoverOwnerRoot(root): one-level `{root}/{name}/SKILL.md` listing, skips dot dirs
├── SkillInfo.kt                # Skill metadata representation
├── SkillLoader.kt              # Line-based frontmatter parse; rewriteName() for rename-on-install
├── SkillChecksums.kt           # SHA-256 fingerprints (UTF-8 pinned; HexFormat)
├── SkillPaths.kt               # ownerRoot / installDir / isWithin / safeSegment + lexical canonicalize
├── SkillRegistry.kt            # In-memory registry keyed SkillKey(owner, name); per-owner rescan
├── SkillAccessResolver.kt      # listScopedSkills(userId) → List<ScopedSkill>; own rows shadow system, row install path gates exposure
├── SkillModelView.kt           # Name-bound + enabled-filtered view for prompt/load/search (no whitelist)
├── SkillCatalogService.kt      # Read/toggle facade for the catalog (management UI facing)
├── SkillSyncService.kt         # THE write direction: DB row ↔ OSS zip ↔ owner root (syncFor/addSkill/addUploaded/deleteSkill)
├── SkillPackages.kt            # Zip codec: pack (symlink/size reject) / unpack (zip-slip reject, cap)
├── SkillPackageStore.kt        # Per-owner ObjectStorage handle + `skills/{owner}/{name}.zip` keying
├── SkillRefreshService.kt      # refresh_skills backend: sync → index; ensureSynced() lazy gate
├── SkillIndexer.kt             # Catalog row ↔ skill_search projection (checksum-driven, sync_state CAS)
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
│   ├── CommandService.kt       # Command execution orchestration (skill commands keyed by name)
│   ├── BuiltinCommandHandler.kt # Built-in command implementations
│   ├── CommandInfo.kt          # Command metadata
│   ├── CommandUtils.kt         # Argument parsing + `skill:{name}` link serialisation
│   └── McpPromptProvider.kt    # MCP prompt template provider
├── subagent/
│   ├── SubAgentTool.kt         # Spawns child agent as tool
│   ├── SubAgentToolBuilder.kt  # Spring wiring
│   └── SubAgentExecutorStrategy.kt # Execution strategy interface
└── team/                       # Multi-agent team coordination tools
```

Related modules outside `easyai-skills`:
- `easyai-core/…/core/skill/` — `AsyncSkillCatalogStore`, `SkillCatalogEntry`, `SkillOwnerContext`, `SkillStore` interfaces (storage-agnostic)
- `easyai-repository/…/repository/skill/R2dbcAsyncSkillCatalogStore.kt` — R2DBC implementation (`skill` table, unique `(user_id, name)`)
- `easyai-rag/…/rag/RagSkillStore.kt` — `SkillStore` implementation backed by the RAG index
- `easyai-autoconfigure/easyai-core-autoconfigure/…/core/SkillIndexStartupRunner.kt` — `ApplicationReadyEvent` per-owner sync + pending-index retry
- `easyai-autoconfigure/easyai-core-autoconfigure/…/core/EasyAiCoreAutoConfiguration.kt` — wiring (`SkillSyncService`, `SkillPackageStore` with local-dir package fallback, `SkillAccessResolver`, `SkillModelView`)
- `easyai-web/…/web/controller/SkillController.kt` — REST: list / add (directory or upload) / enable / delete
- `easyai-web/…/web/security/AuthController.kt` — best-effort `syncFor(userId)` after login and on first `me`

## ARCHITECTURE INVARIANTS

**Write direction: catalog row (identity/enablement) + OSS zip (content) + owner root (working copy).**
`SkillSyncService` is the only component that reconciles the three, and it is the only one that
writes into an owner root from the catalog (restore) or into the catalog from disk (claim/push).
The RAG index is a projection of catalog rows whose whole-directory checksum moved.

**DB is authoritative for existence, local drift is authoritative for content.** `syncFor(owner)`
pairs DB rows and `{root}` subdirectories by name: DB-only → restore from `object_key` and verify
SHA-256 before the files become visible; both sides but checksum differs → the local copy is
treated as newer (the user edited with `write`/an editor on this machine) and is re-packed,
uploaded, and CAS-written via `updateContent`; directory-only → claimed through the add flow
**in a personal root**. There is no third state: nothing is deleted because a row is missing.

**The shared `system` root never creates a skill from disk.** Existence there is catalog-only: an
unclaimed directory under `{rootDir}/system` is counted as `unclaimed`, logged, and left untouched —
whoever can write that path (any user's `write`/`bash` tool can, and no request identity is attached
to a sync pass) would otherwise publish a skill every user on the machine can load. Shared skills are
created only by the system identity through `addSkill`/`addUploaded`. Content drift of an *existing*
system row still pushes, so admins keep editing installed built-ins on disk. What makes the ignore
stick is `SkillAccessResolver`: no row, no exposure — even though `rescan` registers the directory
into the in-memory view.

**A checksum-quiet row can still have no package: re-upload it, don't re-write it.** `object_key`
records a key, not the backend it landed in, and the backend an owner resolves to can change under
it (object storage configured after the skills were claimed into the local fallback, a bucket
switch). So when the directory digest equals the row checksum, the pass reads the owner's whole
package namespace with **one** `ObjectStorage.listKeys` call and, for a key missing from that
listing, `backfillPackage` re-packs the working copy and puts it — no
`updateContent`, because the row already describes these bytes and bumping the revision would force
a needless reindex. Counted separately as `backfilled`. A listing that fails, or a row keeping its
key outside the owner's prefix, falls back to one HEAD per row, so an unreachable storage still
raises per-row and lands in `failed` — which is intended: content that cannot be verified in the
bucket is not durable.

**Two-level ownership with user shadowing.** `user_id="system"` is the read-only shared layer
(`SkillCatalogEntry.DEFAULT_USER_ID`); a requester's own rows shadow same-named system rows in
`SkillAccessResolver.listScopedSkills`, and the shadowing is resolved **before** the enabled filter
(`SkillModelView.listEffective`), otherwise disabling a personal skill would resurrect its shared
namesake. A disabled own row therefore hides the shared one instead of falling through to it.
Regular users cannot write, toggle, or delete system rows — that is enforced in `SkillController` by requiring
`userId == "system"` for shared-target mutations (auth-disabled single-machine mode is
admin-capable by the same rule; there is no separate role concept).

**One owner lock serializes every mutation of a root.** `SkillSyncService.withOwnerLock(owner)`
guards login sync, lazy first-access sync, `refresh_skills`, add, upload, delete, and the indexer's
reconcile. Primitives named `pushContent` / `claimPackage` / `restore` deliberately do **not** take
the lock; wrap them at the call site. Never nest a lock-taking call inside a locked block.

**Sync triggers are three, and all idempotent.** Login hook (`AuthController`), lazy first access
(`SkillRefreshService.ensureSynced` — once per owner per process, un-marked on failure so the next
access retries), and the startup sweep over distinct `user_id`s (`SkillIndexStartupRunner`). All
three funnel into the same `syncFor`.

**Install-path canonicalisation is one function.** `SkillPaths.canonicalize` (lexical
`toAbsolutePath().normalize()`, **not** symlink-resolved) is used by every write into `install_path`
and every comparison against it. Do not add a second normalisation helper — the historic bug was
three call sites disagreeing on whether to resolve symlinks. Lexical-only is deliberate: it is
deterministic across restarts and machines and does not require the file to exist. Containment is
explicit where it matters: `restore` only writes to `SkillPaths.installDir(root, name)` and refuses a
row whose `install_path` differs, `SkillPaths.isWithin` gates directory deletion and root adoption.

**Checksum-driven reindex.** `SkillIndexer.reconcileByDrift(owners)` only touches rows whose
`SkillSyncService.snapshotOf(...)` checksum differs from `indexed_checksum`; `mtime` is a
process-local hint — correctness across restarts comes from the persisted checksum and the
`sync_state` / `revision` / `next_attempt_at` columns that also drive `reconcilePending()` retries.

**Packages must be safe to unpack from untrusted input.** `SkillPackages.pack` rejects symlinks,
non-regular files, and the `package-max-bytes` cap; `unpack` rejects absolute paths, `..`, and
anything that resolves outside the target directory, with a total-size cap. Uploads are staged under
the system temp directory (`SkillSyncService.addUploaded`) so a concurrent sync pass can never see a
half-written skill, and the staged tree is the only thing ever copied into an owner root.

**Object storage resolves per owner, with a local fallback.** `SkillPackageStore.storageFor(owner)`
uses `ObjectStorageResolver` (user row, then `system` row) and falls back to a local directory under
the skill root so desktop/dev deployments keep full sync semantics without OSS.

**RAG integration is opt-in and degradable.** The flag is `easyai.skills.rag.enabled`; when off,
`SkillStore` is null and `SkillIndexer` becomes a no-op on the index side. `RagSkillStore.runCatchingRag`
catches every non-cancellation `Exception`, not just `RagException`, so a JSON parse error or
`IOException` in the RAG client cannot break the agent loop. RAG doc ownership slices are the owner's
biz id only (`system` shared skills → the `u_system_s` slice).

**Catalog is optional; ownership misreporting is fatal.** Every path tolerates `catalog == null`
(single-machine/dev mode) by exposing the registry snapshot unbound. When a catalog exists, rows are
kept only under their own `userId` — a store that reports the wrong owner authorises nothing.

## THREE-LAYER AUTHORISATION
1. **biz_id isolation** (RAG side): `skill_search` never crosses owners; `RagSkillStore` derives the
   biz ids from the caller's owner set (`[userId, "system"]`).
2. **Catalog enablement filter**: rows with `enabled=false` are dropped by `SkillModelView.listEffective`,
   which every prompt, load and search path reads through.
3. **load_skill whitelist**: the agent config's `allowedSkillNames` narrows what a specific sub-agent may
   serve; enforced inside `SkillTool.doExecute` **before** consulting the registry, so the error message
   cannot leak names outside the whitelist.

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Skill lifecycle | `SkillLoader` + `SkillRegistry` | Parse → register per owner; rescan returns `RegistryDelta` |
| Owner-root discovery | `SkillDiscovery.discoverOwnerRoot` | One-level `{root}/{name}/SKILL.md` listing, dot dirs skipped |
| Catalog identity | `SkillAccessResolver` + `SkillPaths` | `(user_id, name)`; owner root / install dir derivation |
| Visible set for a user | `SkillAccessResolver.listScopedSkills` | Own + system merge, name shadowing |
| DB ↔ OSS ↔ disk reconcile | `SkillSyncService.syncFor` / `addSkill` / `addUploaded` / `deleteSkill` | Per-owner mutex |
| Package codec and storage | `SkillPackages` + `SkillPackageStore` | zip-slip guards, `skills/{owner}/{name}.zip` |
| Index sync | `SkillIndexer.reconcileByDrift` / `reconcilePending` | Checksum-driven, disabled rows skipped |
| Prompt visibility | `SkillPromptSource.skillsForPrompt` | Fresh enabled-filtered read, RAG suppression aware |
| Re-scan after `write` | `SkillRefreshService` + `RefreshSkillsTool` | sync then index; `ensureSynced` lazy gate |
| Tool integration | `SkillTool` (`load_skill`) | Whitelist checked before registry lookup |
| Semantic search | `SkillSearchTool` (`skill_search`) | Owner-scoped biz ids, name-binding |
| HTTP facade | `easyai-web/…/SkillController.kt` | list / add / upload / enable / delete |
| Login sync hook | `easyai-web/…/security/AuthController.kt` | best-effort, never blocks the response |
| Startup reconcile | `easyai-autoconfigure/…/core/SkillIndexStartupRunner.kt` | `ApplicationReadyEvent` per-owner sync |
| Slash commands | `command/CommandService` | Skill commands carry `source = skill.name` |
| Sub-agents | `subagent/SubAgentTool` | Spawn child agent with depth limit |

## CREATING A SKILL
Two authorised paths, both ending in the same pipeline (validate → place under the owner root →
pack → upload → claim row → submit to the index):

**A. Management UI (the normal path).** `POST /api/skills {name, sourcePath, shared}` for a
server-side directory, or `POST /api/skills/upload` for a browser folder/zip. A name already taken
within the target owner returns `409` and the UI prompts for a new name (the new name is then used
for the directory, the row, and the object key; frontmatter `name` is rewritten on mismatch). A
name that only collides with a *shared* skill installs fine and shadows it — the response carries a
warning. `400` = spec/size validation failure with the concrete reason, `403` = a non-system caller
targeting the shared layer, `413` = package over `package-max-bytes`. This is the **only** way a
shared skill comes into existence: dropping a directory into `{rootDir}/system` installs nothing —
`syncFor` reports it as `unclaimed` and leaves the files alone.

**B. Agent authoring flow (kept).**
1. `write` the files into `~/.easyai/skills/{userId}/{slug}/` — absolute paths only, one file per
   call; `references/*` and `assets/*` are separate writes. Writing inside your own root is claimed
   in place; a directory picked from elsewhere is copied.
2. `refresh_skills` — `SkillSyncService.syncFor` claims the new directory (pack + upload + row) and
   `SkillIndexer` pushes it to the `skill_search` index. Until this runs the skill is a file,
   invisible to `load_skill` and to the system prompt.
3. The user ticks the skill into the agent's skill whitelist; `alwaysInclude` covers `refresh_skills`
   itself, not the skills it just discovered.

Structural rules (slug charset, length, the 500-line body cap) live in the skill-creator SKILL.md,
not on the server: `SkillLoader` only parses frontmatter, so a malformed skill shows up in
`refresh_skills` as "not added" plus a `Failed to parse SKILL.md at` warning. Do not re-add
validation to the *tool* path — it is what forced whole skill bodies through a single tool call and
blew past output-token limits. The HTTP add path does validate (name slug, SKILL.md presence,
package size), because its input is an unbounded user upload.

## MIGRATIONS
`V3__create_skill_table.sql` defines the final `skill` schema (unique `(user_id, name)`,
`root_path` / `install_path` / `object_key` / `checksum` / `indexed_checksum` / `sync_state` /
`revision` / `next_attempt_at` / `last_error`). The project shipped unreleased, so the earlier
skill migrations were rewritten rather than extended. **Any migration already applied to a
persistent DB is frozen — never edit it, not even comments**: Flyway validates the checksum on
every boot and refuses startup on mismatch. Add a new versioned migration instead.

## CONVENTIONS
- Owner roots are derived, never configured per user: `SkillPaths.ownerRoot(config, owner)` → `installDir(root, name)`
- Skills converted to agent tools via the `SkillTool` adapter
- Commands: registered in `CommandRegistry`, executed via `CommandService`
- Sub-agents respect `maxSubAgentDepth` from agent config
- All classes `internal` unless they are part of the cross-module public API (autoconfigure, web, rag)
- `catch (e: Exception)` must always be preceded by `catch (e: CancellationException) { throw e }` — never swallow coroutine cancellation
- Path comparisons go through `SkillPaths`; checksums via `SkillChecksums.sha256Hex` (UTF-8 pinned)

## ANTI-PATTERNS
- Don't bypass `SkillRegistry` for skill activation — use the lifecycle
- Don't reintroduce a scan outside an owner root (no workDir / home / project walk, no `SkillScope`, no `project_hash`)
- Don't write to an owner root, the catalog, or object storage from anywhere but `SkillSyncService`
- Don't take the owner lock inside another locked block, and don't call the `…Locked` primitives without wrapping them
- Don't let a personal row authorise a system install path (or vice versa) — bind the row before trusting the path
- Don't mix skill loading with tool execution — separate concerns
- Sub-agents must not exceed depth limit
- Don't reintroduce a second `normalizeDir` — use `SkillPaths`
- Don't catch bare `Exception` in suspend code without rethrowing `CancellationException` first
- Don't re-read a skill directory ad hoc — `SkillSyncService.snapshotOf` is the one consistent read (parse + directory digest + declared version in one pass)
- Don't unpack a zip or write an uploaded file without the relative-path and size guards in `SkillPackages`
