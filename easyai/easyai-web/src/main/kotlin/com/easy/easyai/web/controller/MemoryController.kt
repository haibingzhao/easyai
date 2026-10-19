package com.easy.easyai.web.controller

import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.memory.ConsolidationReport
import com.easy.easyai.core.memory.MemoryConsolidationAgent
import com.easy.easyai.core.memory.MemoryConsolidationSettings
import com.easy.easyai.core.memory.MemoryConsolidationTimeoutException
import com.easy.easyai.core.memory.MemoryContentLimits
import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryMaturity
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSettingsStore
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationUserSettings
import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskRunner
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.MemoryConsolidationTaskHandler
import com.easy.easyai.core.task.TaskTrigger
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.web.model.ConsolidationReportDto
import com.easy.easyai.web.model.ConsolidationRequest
import com.easy.easyai.web.model.ConsolidationResponse
import com.easy.easyai.web.model.CreateMemoryRequest
import com.easy.easyai.web.model.MemoryConfigDto
import com.easy.easyai.web.model.MemoryConsolidationSettingsDto
import com.easy.easyai.web.model.MemoryEntryDto
import com.easy.easyai.web.model.UpdateMemoryConfigRequest
import com.easy.easyai.web.model.UpdateMemoryConsolidationSettingsRequest
import com.easy.easyai.web.model.UpdateMemoryRequest
import com.easy.easyai.web.security.currentGroupOwners
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.service.DefaultWorkspaceService
import com.easy.easyai.web.service.SessionService
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.scheduling.support.CronExpression
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.nio.file.Paths
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/memories")
class MemoryController(
    @param:Autowired(required = false) private val memoryStore: MemoryStore?,
    @param:Autowired(required = false) private val projectStore: AsyncProjectStore? = null,
    @param:Autowired(required = false) private val workspaceService: DefaultWorkspaceService? = null,
    @param:Autowired(required = false) private val consolidationAgent: MemoryConsolidationAgent? = null,
    @param:Autowired(required = false) private val consolidationSettings: MemoryConsolidationSettings? = null,
    @param:Autowired(required = false) private val consolidationSettingsStore: MemoryConsolidationSettingsStore? = null,
    @param:Autowired(required = false) private val auxModelResolver: AuxModelResolver? = null,
    @param:Autowired(required = false) private val asyncTaskRunner: AsyncTaskRunner? = null,
    @param:Autowired(required = false) private val asyncTaskStore: AsyncTaskStore? = null,
    @param:Autowired(required = false) private val sessionService: SessionService? = null
) {

    private val logger = LoggerFactory.getLogger(MemoryController::class.java)

    @GetMapping
    fun listMemories(
        @RequestParam(defaultValue = "global") scope: String,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) maturity: String?,
        @RequestParam(required = false) projectPath: String? = null
    ): Mono<List<MemoryEntryDto>> {
        return mono {
            val store = memoryStore ?: return@mono emptyList()
            val memoryScope = parseScope(scope)
            val owner = ownerContext(memoryScope, projectPath)
            val memoryType = type?.let { parseType(it) }
            val maturityFilter = maturity?.let { MemoryMaturity.fromApiName(it) }
            store.list(memoryScope, owner, memoryType)
                .filter { maturityFilter == null || it.maturity == maturityFilter }
                .map { it.toDto(memoryScope) }
        }
    }

    /**
     * Archived (absorbed) entries by docId — the drill-down behind a merged entry's `refs`.
     *
     * Normal listing/search never returns archived entries, so this is the only read path into
     * history. Ids are capped at the EasyRAG batch size; unknown ids are simply absent.
     */
    @GetMapping("/archived")
    fun listArchivedMemories(
        @RequestParam(required = false) ids: List<String>? = null,
        @RequestParam(defaultValue = "global") scope: String,
        @RequestParam(required = false) projectPath: String? = null
    ): Mono<List<MemoryEntryDto>> {
        return mono {
            val store = memoryStore ?: return@mono emptyList()
            val docIds = ids.orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
            if (docIds.isEmpty()) return@mono emptyList()
            if (docIds.size > MAX_ARCHIVE_READ) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "At most $MAX_ARCHIVE_READ docIds can be read at once"
                )
            }
            val memoryScope = parseScope(scope)
            val owner = ownerContext(memoryScope, projectPath)
            store.findByIds(docIds.distinct(), memoryScope, owner).map { it.toDto(memoryScope) }
        }
    }

    @GetMapping("/{name}")
    fun getMemory(
        @PathVariable name: String,
        @RequestParam(defaultValue = "global") scope: String,
        @RequestParam(required = false) projectPath: String? = null
    ): Mono<MemoryEntryDto> {
        return mono {
            val store = memoryStore ?: throw memoryNotEnabled()
            val memoryScope = parseScope(scope)
            val owner = ownerContext(memoryScope, projectPath)
            store.findByName(name, memoryScope, owner)?.toDto(memoryScope)
                ?: throw IllegalArgumentException("Memory not found: $name")
        }
    }

    @PostMapping
    fun createOrUpdateMemory(@RequestBody request: CreateMemoryRequest): Mono<MemoryEntryDto> {
        return mono {
            val store = memoryStore ?: throw memoryNotEnabled()
            // Validate name to prevent path traversal and invalid file names
            require(request.name.isNotBlank()) { "Memory name must not be blank" }
            require(!request.name.contains("/") && !request.name.contains("\\") && !request.name.contains("..")) {
                "Memory name must not contain path separators or '..'"
            }
            val memoryScope = parseScope(request.scope)
            val owner = ownerContext(memoryScope, request.projectPath)
            requireProjectScopeWritable(memoryScope, request.projectPath)
            val memoryType = parseType(request.type)
            val maturity = request.maturity?.let { MemoryMaturity.fromApiName(it) }
            val scenarios = request.scenarios.map { it.trim() }.filter { it.isNotEmpty() }
            requireWithinLimits(request.description, request.content)
            // Resolve exactly the key this write targets, history included: an absorbed source lives at
            // the same `{type}/{name}.md`, and writing over it would revive it as a live duplicate of
            // the merged entry that already references it.
            val existing = store.findByName(
                request.name, memoryScope, owner, memoryType, includeArchived = true
            )
            if (existing?.archived == true) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Memory name '${request.name}' belongs to an archived entry absorbed by a " +
                        "consolidation run; choose another name"
                )
            }
            // Preserve original created date if updating an existing entry
            val entry = MemoryEntry(
                name = request.name,
                description = request.description,
                type = memoryType,
                content = request.content,
                path = "${memoryType.dirName}/${request.name}.md",
                keywords = request.keywords,
                created = existing?.created ?: LocalDate.now(),
                updated = LocalDate.now(),
                maturity = maturity,
                scenarios = scenarios,
                // These describe where the entry sits in the consolidation graph, not what the user
                // typed, and the write replaces the whole stored document. Without carrying them over,
                // editing a merged entry on the Memories page drops its refs/level and the run history
                // it drills down into.
                id = existing?.id,
                level = existing?.level ?: 0,
                refs = existing?.refs ?: emptyList(),
                mergedInto = existing?.mergedInto
            )
            store.write(entry, memoryScope, owner)
            entry.toDto(memoryScope)
        }
    }

    @PutMapping("/{name}")
    fun updateMemory(
        @PathVariable name: String,
        @RequestParam(defaultValue = "global") scope: String,
        @RequestParam(required = false) projectPath: String? = null,
        @RequestBody request: UpdateMemoryRequest
    ): Mono<MemoryEntryDto> {
        return mono {
            val store = memoryStore ?: throw memoryNotEnabled()
            val memoryScope = parseScope(scope)
            val owner = ownerContext(memoryScope, projectPath)
            requireProjectScopeWritable(memoryScope, projectPath)
            val existing = store.findByName(name, memoryScope, owner)
                ?: throw IllegalArgumentException("Memory not found: $name")
            val maturity = request.maturity?.let { MemoryMaturity.fromApiName(it) }
            val entry = existing.copy(
                description = request.description ?: existing.description,
                content = request.content ?: existing.content,
                keywords = request.keywords?.map { it.trim() }?.filter { it.isNotEmpty() } ?: existing.keywords,
                maturity = maturity ?: existing.maturity,
                scenarios = request.scenarios?.map { it.trim() }?.filter { it.isNotEmpty() } ?: existing.scenarios,
                updated = LocalDate.now()
            )
            requireWithinLimits(entry.description, entry.content)
            store.write(entry, memoryScope, owner)
            entry.toDto(memoryScope)
        }
    }

    @DeleteMapping("/{name}")
    fun deleteMemory(
        @PathVariable name: String,
        @RequestParam(defaultValue = "global") scope: String,
        @RequestParam(required = false) projectPath: String? = null
    ): Mono<Map<String, Boolean>> {
        return mono {
            val store = memoryStore ?: throw memoryNotEnabled()
            val memoryScope = parseScope(scope)
            val owner = ownerContext(memoryScope, projectPath)
            requireProjectScopeWritable(memoryScope, projectPath)
            val entry = store.findByName(name, memoryScope, owner)
                ?: throw IllegalArgumentException("Memory not found: $name")
            val deleted = store.delete(entry.path, memoryScope, owner)
            mapOf("deleted" to deleted)
        }
    }

    @DeleteMapping
    fun deleteAllMemories(
        @RequestParam(defaultValue = "global") scope: String,
        @RequestParam(required = false) projectPath: String? = null
    ): Mono<Map<String, Int>> {
        return mono {
            val store = memoryStore ?: throw memoryNotEnabled()
            val memoryScope = parseScope(scope)
            val owner = ownerContext(memoryScope, projectPath)
            requireProjectScopeWritable(memoryScope, projectPath)
            val count = store.deleteAll(memoryScope, owner)
            mapOf("deleted" to count)
        }
    }

    @GetMapping("/consolidation/settings")
    fun getConsolidationSettings(): Mono<MemoryConsolidationSettingsDto> {
        return mono {
            val userId = getCurrentUserId()
            toSettingsDto(consolidationSettingsStore?.get(userId))
        }
    }

    /**
     * Update the caller's runtime consolidation preferences. Enabling requires a configured
     * MEMORY_CURATION model: a scheduled run has no chat-session model to fall back to.
     */
    @PutMapping("/consolidation/settings")
    fun updateConsolidationSettings(
        @RequestBody request: UpdateMemoryConsolidationSettingsRequest
    ): Mono<MemoryConsolidationSettingsDto> {
        return mono {
            val store = consolidationSettingsStore ?: throw settingsUnavailable()
            val userId = getCurrentUserId()
            val current = store.get(userId) ?: MemoryConsolidationUserSettings(userId = userId)
            val enabled = request.enabled ?: current.enabled
            val typeThresholds = request.typeThresholds ?: current.typeThresholds
            validateThresholds(typeThresholds)
            val scanCron = when (val raw = request.scanCron) {
                null -> current.scanCron
                else -> raw.trim().takeIf { it.isNotEmpty() }?.also { validateCron(it) }
            }
            if (enabled && !curationModelConfigured()) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Configure the MEMORY_CURATION task model before enabling memory consolidation"
                )
            }
            store.save(
                current.copy(
                    enabled = enabled,
                    // Snapshot of who this caller resolves assets against, taken while the group claims
                    // are still in hand: the scheduled tick has no security context, and the gate above
                    // reads self + group, so a group-level MEMORY_CURATION choice needs the same set at
                    // run time or the switch opens and every scheduled run fails.
                    owners = currentGroupOwners().toList(),
                    typeThresholds = typeThresholds,
                    scanCron = scanCron,
                    updatedAt = System.currentTimeMillis()
                )
            )
            toSettingsDto(store.get(userId))
        }
    }

    /**
     * Run one consolidation pass over a single type in one slice.
     *
     * A dry run is synchronous: it returns the cluster plan inline without writing or archiving. A
     * real run creates a durable **async task** (trigger=FRONTEND) with a pre-minted hidden session
     * and returns its `taskId`; the run then survives session close and restart, and its transcript
     * is reachable from the Tasks page through the task's `sessionId`.
     *
     * A preview's session belongs to no task row, so it is released with the response instead of
     * accumulating one hidden session per click. Submitting a real run for a slice that already has
     * unsettled consolidation work — from this endpoint or the scheduled scan — answers 409 rather
     * than starting a second run over the same entries.
     */
    @PostMapping("/consolidation")
    fun consolidate(@RequestBody request: ConsolidationRequest): Mono<ConsolidationResponse> {
        return mono {
            memoryStore ?: throw memoryNotEnabled()
            val agent = consolidationAgent ?: throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Memory consolidation is not enabled"
            )
            val memoryScope = parseScope(request.scope)
            val owner = ownerContext(memoryScope, request.projectPath)
            requireProjectScopeWritable(memoryScope, request.projectPath)
            val memoryType = parseType(request.type)
            val owners = currentGroupOwners()
            if (!curationModelConfigured()) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Configure the MEMORY_CURATION task model before running memory consolidation"
                )
            }

            if (request.dryRun) {
                // The preview runs through a real persisted session because that is what the agent loop
                // writes to, and nothing ever points back at it: a preview creates no async-task row, and
                // Chat History filters `system_kind` sessions out. The id is minted here so it can be
                // released on every path, including a run that times out or fails midway.
                val previewSessionId = UUID.randomUUID().toString()
                try {
                    val report = try {
                        agent.consolidate(
                            scope = memoryScope,
                            owner = owner,
                            type = memoryType,
                            owners = owners,
                            sessionId = previewSessionId,
                            dryRun = true
                        )
                    } catch (e: MemoryConsolidationTimeoutException) {
                        // A preview is awaited by the request: running out of its own budget is the server
                        // giving up, not the caller's input being wrong.
                        throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, e.message, e)
                    } catch (e: IllegalStateException) {
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
                    }
                    return@mono ConsolidationResponse(report = report.toDto())
                } finally {
                    releasePreviewSession(previewSessionId)
                }
            }

            val runner = asyncTaskRunner ?: throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Async task runner is not available"
            )
            val taskUserId = owner.userId ?: getCurrentUserId()
            // Same-slice duplicate suppression as the scheduled planner: two runs over one slice read
            // the same active set, each write their own merged entries, and fight over the same sources
            // to archive — which recreates the duplicate search hit this refactor exists to remove.
            val sliceKey = MemoryConsolidationTaskHandler.sliceKey(
                memoryScope, owner.projectPath?.toString(), memoryType
            )
            val pendingTask = asyncTaskStore?.listNonTerminal(taskUserId, AsyncTaskType.MEMORY_CONSOLIDATION)
                ?.firstOrNull { MemoryConsolidationTaskHandler.sliceKeyOf(it) == sliceKey }
            if (pendingTask != null) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A consolidation run for this memory type is already queued or running (task ${pendingTask.id})"
                )
            }
            val sessionId = UUID.randomUUID().toString()
            val payload = mapOf(
                MemoryConsolidationTaskHandler.KEY_SCOPE to memoryScope.name.lowercase(),
                MemoryConsolidationTaskHandler.KEY_USER_ID to taskUserId,
                MemoryConsolidationTaskHandler.KEY_PROJECT_PATH to owner.projectPath?.toString(),
                MemoryConsolidationTaskHandler.KEY_OWNERS to owners.toList(),
                MemoryConsolidationTaskHandler.KEY_TYPE to memoryType.dirName,
                MemoryConsolidationTaskHandler.KEY_SESSION_ID to sessionId,
                MemoryConsolidationTaskHandler.KEY_DRY_RUN to false
            )
            // No title: the Tasks page renders this type's localized label plus the payload's memory
            // type, so nothing locale-specific is written to the row.
            val task = AsyncTask(
                id = UUID.randomUUID().toString(),
                userId = getCurrentUserId(),
                type = AsyncTaskType.MEMORY_CONSOLIDATION,
                trigger = TaskTrigger.FRONTEND,
                title = "",
                sessionId = sessionId,
                payload = payload
            )
            runner.submit(task) ?: throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Async task store is not available"
            )
            ConsolidationResponse(taskId = task.id)
        }
    }

    @GetMapping("/config")
    fun getConfig(): Mono<MemoryConfigDto> {
        return mono {
            // Bean wiring guarantees store != null only when easyai.memory.enabled
            // and easyai.rag.enabled are both set, so this reflects real availability.
            MemoryConfigDto(enabled = memoryStore != null)
        }
    }

    @PutMapping("/config")
    fun updateConfig(@RequestBody request: UpdateMemoryConfigRequest): Mono<MemoryConfigDto> {
        return mono {
            if (request.enabled != null) {
                throw IllegalArgumentException("'enabled' cannot be changed at runtime; requires application restart")
            }
            MemoryConfigDto(enabled = memoryStore != null)
        }
    }

    private fun memoryNotEnabled(): ResponseStatusException =
        ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Memory system is not enabled")

    /**
     * Release the hidden session a preview ran in. Best-effort: the plan is already computed, and a
     * failure to garbage-collect its transcript must not turn a good response into an error. Without
     * this every preview adds a `system_kind` session that no task row and no history list can reach.
     */
    private suspend fun releasePreviewSession(sessionId: String) {
        val service = sessionService ?: return
        runCatching { service.deleteSession(sessionId, getCurrentUserId()) }
            .onFailure {
                logger.warn(
                    "Failed to release memory consolidation preview session {}: {}", sessionId, it.message
                )
            }
    }

    private fun settingsUnavailable(): ResponseStatusException = ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Memory consolidation settings require the database (set easyai.r2dbc.enabled=true)"
    )

    private suspend fun curationModelConfigured(): Boolean =
        auxModelResolver?.resolveConfig(currentGroupOwners(), AuxModelTask.MEMORY_CURATION) != null

    /**
     * Hold the manual write paths to the same budgets `memory_write` and the consolidation writer
     * enforce. Without this an over-long entry could only arrive through the Memories page, and the
     * next consolidation of that slice would then reject it as a source.
     */
    private fun requireWithinLimits(description: String, content: String) {
        if (description.length > MemoryContentLimits.DESCRIPTION_CAP_CHARS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Memory description must be at most ${MemoryContentLimits.DESCRIPTION_CAP_CHARS} characters (got ${description.length})"
            )
        }
        if (content.length > MemoryContentLimits.CONTENT_HARD_CAP_CHARS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Memory content must be at most ${MemoryContentLimits.CONTENT_HARD_CAP_CHARS} characters " +
                    "(got ${content.length}); distill it or split it into focused entries"
            )
        }
    }

    private fun validateThresholds(thresholds: Map<String, Int>) {
        if (thresholds.isEmpty()) return
        val valid = MemoryType.entriesFor(DomainCatalog.activeDomain).map { it.dirName }.toSet()
        thresholds.forEach { (type, threshold) ->
            if (type !in valid) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unknown memory type '$type' (expected one of: ${valid.joinToString()})"
                )
            }
            if (threshold < 1) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Threshold for '$type' must be at least 1"
                )
            }
        }
    }

    private fun validateCron(cron: String) {
        runCatching { CronExpression.parse(cron) }.onFailure {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid scan cron '$cron': ${it.message}")
        }
    }

    private suspend fun toSettingsDto(
        user: MemoryConsolidationUserSettings?
    ): MemoryConsolidationSettingsDto {
        // No bean means RAG/consolidation is not wired: report available=false so the UI hides the
        // switch instead of showing a control whose run would 503.
        val deployment = consolidationSettings ?: MemoryConsolidationSettings(available = false)
        return MemoryConsolidationSettingsDto(
            enabled = user?.enabled ?: false,
            typeThresholds = user?.typeThresholds ?: emptyMap(),
            scanCron = user?.scanCron,
            defaultThreshold = deployment.defaultThreshold,
            defaultScanCron = deployment.dailyCron,
            curationModelConfigured = curationModelConfigured(),
            available = deployment.available
        )
    }

    /**
     * Reject PROJECT-scope writes that target a temporary workspace: that directory is removed
     * when its session ends, so an entry stored there would be lost without ever being readable.
     */
    private suspend fun requireProjectScopeWritable(scope: MemoryScope, projectPath: String?) {
        if (scope != MemoryScope.PROJECT || projectPath.isNullOrBlank()) return
        // Checked before the row lookup: a path that never matches a stored row exactly must not slip through.
        if (workspaceService?.isTemporaryPath(projectPath) == true) {
            throw temporaryWorkspaceRejected()
        }
        val store = projectStore ?: return
        val project = store.findByPath(projectPath, getCurrentUserId()) ?: return
        if (project.kind == ProjectKind.TEMP) {
            throw temporaryWorkspaceRejected()
        }
    }

    private fun temporaryWorkspaceRejected(): ResponseStatusException = ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "PROJECT scope is not available in temporary workspaces"
    )

    /**
     * Build the ownership context for backend isolation. PROJECT scope
     * requires an explicit projectPath; without it the request is rejected.
     */
    private suspend fun ownerContext(scope: MemoryScope, projectPath: String?): MemoryOwnerContext {
        if (scope == MemoryScope.PROJECT && projectPath.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "projectPath is required for project scope")
        }
        return MemoryOwnerContext(
            userId = getCurrentUserId(),
            projectPath = projectPath?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        )
    }

    private fun parseScope(scope: String): MemoryScope = when (scope.lowercase()) {
        "global" -> MemoryScope.GLOBAL
        "project" -> MemoryScope.PROJECT
        else -> throw IllegalArgumentException("Invalid scope: $scope (expected 'global' or 'project')")
    }

    private fun parseType(type: String): MemoryType {
        val memoryType = MemoryType.fromDirName(type)
            ?: throw IllegalArgumentException("Invalid memory type: $type (expected one of: ${MemoryType.entriesFor(DomainCatalog.activeDomain).joinToString { it.dirName }})")
        val validTypes = MemoryType.entriesFor(DomainCatalog.activeDomain)
        if (memoryType !in validTypes) {
            throw IllegalArgumentException("Memory type '$type' is not available in the current domain (expected one of: ${validTypes.joinToString { it.dirName }})")
        }
        return memoryType
    }

    private fun MemoryEntry.toDto(scope: MemoryScope): MemoryEntryDto = MemoryEntryDto(
        name = name,
        description = description,
        type = type.dirName,
        scope = scope.name.lowercase(),
        content = content,
        keywords = keywords,
        maturity = maturity?.apiName,
        scenarios = scenarios,
        created = created?.toString(),
        updated = updated?.toString(),
        id = id,
        level = level,
        refs = refs,
        mergedInto = mergedInto,
        archived = archived
    )

    private fun ConsolidationReport.toDto(): ConsolidationReportDto = ConsolidationReportDto(
        type = type.dirName,
        scope = scope.name.lowercase(),
        activeBefore = activeBefore,
        clustersProposed = clustersProposed,
        mergedWritten = mergedWritten,
        archived = archived,
        archiveShortfall = archiveShortfall,
        skippedSingletons = skippedSingletons,
        skippedOverCap = skippedOverCap,
        droppedGroups = droppedGroups,
        overTarget = overTarget,
        rejectedBatches = rejectedBatches,
        llmFailures = llmFailures,
        llmFailureReason = llmFailureReason,
        uncoveredDocIds = uncoveredDocIds,
        dryRun = dryRun,
        sessionId = sessionId,
        plan = plan
    )

    private companion object {
        const val MAX_ARCHIVE_READ = 200
    }
}
