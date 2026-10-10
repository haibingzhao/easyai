package com.easy.easyai.web.controller

import com.easy.easyai.agent.api.model.*
import com.easy.easyai.agent.registry.ToolRegistry
import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.common.textio.template.InvalidTemplateException
import com.easy.easyai.common.textio.template.TemplateRenderer
import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AgentToolConfig
import com.easy.easyai.core.agent.AgentType
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.agent.TargetType
import com.easy.easyai.core.tool.ToolFactory
import com.easy.easyai.web.model.ConfigValidationError
import com.easy.easyai.web.model.ValidateTemplateRequest
import com.easy.easyai.web.model.ValidateTemplateResponse
import com.easy.easyai.web.model.TemplateValidationError
import com.easy.easyai.web.security.currentOwners
import com.easy.easyai.web.security.parseAssetScope
import com.easy.easyai.web.security.resolveWriteOwner
import com.easy.easyai.web.service.validation.ResourceExistenceValidator
import com.easy.easyai.web.service.validation.ToolAvailability
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import com.easy.easyai.common.util.SharedObjectMapper

/**
 * REST controller for Agent CRUD operations.
 *
 * Endpoints:
 * - GET    /api/agents              - List all agents
 * - GET    /api/agents/subagents    - List all sub-agents (agentType=SUBAGENT|ALL)
 * - GET    /api/agents/{id}         - Get single agent
 * - POST   /api/agents              - Create agent
 * - PUT    /api/agents/{id}         - Update agent
 * - DELETE /api/agents/{id}         - Delete agent
 * - GET    /api/agents/{id}/tools   - Get agent tools
 * - PUT    /api/agents/{id}/tools   - Update agent tools
 * - GET    /api/agents/{id}/configs - Get agent tool/subagent configs
 * - PUT    /api/agents/{id}/configs - Save agent tool/subagent configs
 * - GET    /api/agents/{id}/members - Get team agent member IDs
 * - PUT    /api/agents/{id}/members - Save team agent member IDs
 * - POST   /api/agents/validate-template - Validate Jinja2 template syntax
 */
@RestController
@RequestMapping("/api/agents")
class AgentController(
    private val agentStore: AsyncAgentStore,
    private val toolRegistry: ToolRegistry,
    @param:Autowired(required = false)
    private val templateRenderer: TemplateRenderer? = null,
    private val toolFactory: ToolFactory,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Tool names that load skill content at runtime, derived from tool capability metadata. */
    private fun skillLoaderNames(): Set<String> = ToolAvailability.skillLoaders(toolFactory)

    @GetMapping
    fun listAll(): Mono<List<AgentDto>> = mono {
        agentStore.findAll(currentOwners()).map { it.toLightDto() }
    }

    @GetMapping("/subagents")
    fun listSubAgents(): Mono<List<AgentDto>> = mono {
        agentStore.findSubAgents(currentOwners()).map { it.toLightDto() }
    }

    @GetMapping("/chat")
    fun listChatAgents(): Mono<List<AgentDto>> = mono {
        agentStore.findChatAgents(currentOwners()).map { it.toLightDto() }
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): Mono<AgentDto> = mono {
        val agent = agentStore.findById(id, currentOwners())
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        loadAgentDto(agent, id)
    }

    /**
     * Export an agent configuration as a self-contained JSON file.
     * Global sub-agent/member references are expanded to inline custom format
     * so the exported file is fully portable across environments.
     */
    @GetMapping("/{id}/export")
    fun exportAgent(@PathVariable id: String): Mono<ResponseEntity<String>> = mono {
        val owners = currentOwners()
        val agent = agentStore.findById(id, owners)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        val dto = loadAgentDto(agent, id)

        // Expand global sub-agent references to inline custom format
        // If an agent cannot be resolved (deleted/cross-user), keep original ID reference
        val expandedSubAgents = mutableListOf<InlineAgentSpec>()
        val unresolvedSubAgentIds = mutableListOf<String>()
        for (subId in dto.subAgentIds) {
            val spec = expandAgentToInlineSpec(subId, owners)
            if (spec != null) expandedSubAgents.add(spec)
            else { unresolvedSubAgentIds.add(subId); logger.warn("Export: sub-agent '{}' not found, keeping ID reference", subId) }
        }
        val expandedMembers = mutableListOf<InlineAgentSpec>()
        val unresolvedMemberIds = mutableListOf<String>()
        for (memberId in dto.memberIds) {
            val spec = expandAgentToInlineSpec(memberId, owners)
            if (spec != null) expandedMembers.add(spec)
            else { unresolvedMemberIds.add(memberId); logger.warn("Export: member '{}' not found, keeping ID reference", memberId) }
        }

        val exportDto = dto.copy(
            subAgentIds = unresolvedSubAgentIds,
            memberIds = unresolvedMemberIds,
            customSubAgents = expandedSubAgents + dto.customSubAgents,
            customMembers = expandedMembers + dto.customMembers
        )
        val exportPayload = mapOf("formatVersion" to 1, "agent" to exportDto)
        val json = objectMapper.writeValueAsString(exportPayload)
        val safeId = id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"${safeId}.agent.json\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(json)
    }

    /**
     * Expand a global agent reference to an InlineAgentSpec for export.
     * Loads the agent's definition, tools, skills, and MCP configs.
     * Returns null if the agent is not found (logs a warning).
     */
    private suspend fun expandAgentToInlineSpec(agentId: String, owners: Collection<String>): InlineAgentSpec? {
        val definition = agentStore.findById(agentId, owners)
        if (definition == null) {
            logger.warn("Export: agent '{}' not found, skipping expansion", agentId)
            return null
        }
        // The whitelist rows live in the bucket the resolved agent itself belongs to, which for a
        // shared or built-in agent is not the caller's.
        val toolNames = agentStore.getAgentToolNames(agentId, definition.userId)
        val skillNames = agentStore.getAgentSkillNames(agentId, definition.userId)
        val mcpConfigs = agentStore.getAgentMcpConfigs(agentId, definition.userId).toMcpBindingDtos()
        return InlineAgentSpec(
            name = definition.name,
            description = definition.description ?: "",
            systemPrompt = definition.promptTemplate ?: "",
            toolNames = toolNames,
            skillNames = skillNames,
            mcpConfigs = mcpConfigs
        )
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody request: AgentCreateRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<AgentDto> = mono {
        require((request.description?.length ?: 0) <= MAX_DESCRIPTION_LENGTH) {
            "Description must be $MAX_DESCRIPTION_LENGTH characters or less"
        }
        val owner = resolveWriteOwner(parseAssetScope(scope))
        // Check conflict with built-in system agent first (clearer error message)
        val systemAgent = agentStore.findById(request.id, AuthConstants.SYSTEM_USER_ID)
        if (systemAgent != null && systemAgent.userId == AuthConstants.SYSTEM_USER_ID) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Agent ID '${request.id}' is reserved by a built-in system agent. Please choose a different ID."
            )
        }
        // Check if the target bucket already has an agent with this ID
        val existing = agentStore.findById(request.id, owner)
        if (existing != null && existing.userId == owner) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Agent already exists: ${request.id}")
        }
        rejectInvalidSkillTools(ResourceExistenceValidator.validateSkillTools(request, skillLoaderNames()))
        validateTeamMembers(request.agentType, request.memberIds, request.customMembers, currentOwners())
        val agent = AgentDefinition.create(
            id = request.id,
            name = request.name,
            agentType = request.agentType,
            agentContext = request.agentContext,
            description = request.description,
            promptTemplate = request.promptTemplate,
            customInstructions = request.customInstructions,
            toolNames = request.toolNames,
            maxIterations = request.maxIterations,
            maxSubAgentDepth = request.maxSubAgentDepth,
            color = request.color,
            enabled = request.enabled,
            instructionsEnabled = request.instructionsEnabled ?: true,
            toolFoldEnabled = request.toolFoldEnabled ?: false,
            toolFoldKeepRecentRuns = (request.toolFoldKeepRecentRuns ?: 1)
                .coerceIn(MIN_TOOL_FOLD_KEEP_RECENT_RUNS, MAX_TOOL_FOLD_KEEP_RECENT_RUNS),
            thinkingHistoryEnabled = request.thinkingHistoryEnabled ?: false,
            inputSchema = request.inputSchema,
            outputSchema = request.outputSchema,
            outputSchemaMultiTurn = request.outputSchemaMultiTurn ?: false
        )
        agentStore.save(agent, owner)
        // Persist tool whitelist and sub-agent associations (always, even if empty).
        // Scoped to the same bucket the agent row was written to.
        agentStore.saveAgentToolConfigs(request.id, TargetType.TOOL, request.toolNames, owner)
        agentStore.saveAgentToolConfigs(request.id, TargetType.SUBAGENT, request.subAgentIds, owner)
        agentStore.saveAgentToolConfigs(request.id, TargetType.SKILL, request.skillNames, owner)
        agentStore.saveAgentMcpConfigs(request.id, request.mcpConfigs.toAgentToolConfigs(request.id), owner)
        agentStore.saveAgentCommands(request.id, request.commandNames, owner)
        agentStore.saveAgentMembers(request.id, request.memberIds, owner)
        // Save inline custom sub-agents and members
        if (request.customSubAgents.isNotEmpty()) {
            agentStore.saveAgentInlineSpecs(request.id, TargetType.SUBAGENT, request.customSubAgents.toInlineToolConfigs(request.id, TargetType.SUBAGENT), owner)
        }
        if (request.customMembers.isNotEmpty()) {
            agentStore.saveAgentInlineSpecs(request.id, TargetType.MEMBER, request.customMembers.toInlineToolConfigs(request.id, TargetType.MEMBER), owner)
        }
        agent.toDto(
            toolNames = request.toolNames,
            subAgentIds = request.subAgentIds,
            skillNames = request.skillNames,
            mcpConfigs = request.mcpConfigs,
            commandNames = request.commandNames,
            memberIds = request.memberIds,
            customSubAgents = request.customSubAgents,
            customMembers = request.customMembers
        )
    }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @RequestBody request: AgentCreateRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<AgentDto> = mono {
        require((request.description?.length ?: 0) <= MAX_DESCRIPTION_LENGTH) {
            "Description must be $MAX_DESCRIPTION_LENGTH characters or less"
        }
        val owner = resolveWriteOwner(parseAssetScope(scope))
        val existing = agentStore.findById(id, owner)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")

        if (existing.userId == AuthConstants.SYSTEM_USER_ID) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot modify built-in agent: $id")
        }
        rejectInvalidSkillTools(ResourceExistenceValidator.validateSkillTools(request, skillLoaderNames()))
        validateTeamMembers(request.agentType, request.memberIds, request.customMembers, currentOwners())

        val updated = existing.copy(
            name = request.name,
            agentType = request.agentType,
            agentContext = request.agentContext,
            description = request.description,
            promptTemplate = request.promptTemplate,
            customInstructions = request.customInstructions,
            maxIterations = request.maxIterations,
            maxSubAgentDepth = request.maxSubAgentDepth,
            color = request.color,
            enabled = request.enabled,
            instructionsEnabled = request.instructionsEnabled ?: existing.instructionsEnabled,
            toolFoldEnabled = request.toolFoldEnabled ?: existing.toolFoldEnabled,
            toolFoldKeepRecentRuns = (request.toolFoldKeepRecentRuns ?: existing.toolFoldKeepRecentRuns)
                .coerceIn(MIN_TOOL_FOLD_KEEP_RECENT_RUNS, MAX_TOOL_FOLD_KEEP_RECENT_RUNS),
            thinkingHistoryEnabled = request.thinkingHistoryEnabled ?: existing.thinkingHistoryEnabled,
            inputSchema = request.inputSchema,
            outputSchema = request.outputSchema,
            outputSchemaMultiTurn = request.outputSchemaMultiTurn ?: existing.outputSchemaMultiTurn,
            updatedAt = java.time.Instant.now().epochSecond
        )
        agentStore.update(updated, owner)
        // Persist tool whitelist and sub-agent associations, scoped to the bucket just written
        agentStore.saveAgentToolConfigs(id, TargetType.TOOL, request.toolNames, owner)
        agentStore.saveAgentToolConfigs(id, TargetType.SUBAGENT, request.subAgentIds, owner)
        agentStore.saveAgentToolConfigs(id, TargetType.SKILL, request.skillNames, owner)
        agentStore.saveAgentMcpConfigs(id, request.mcpConfigs.toAgentToolConfigs(id), owner)
        agentStore.saveAgentCommands(id, request.commandNames, owner)
        agentStore.saveAgentMembers(id, request.memberIds, owner)
        // Save inline custom sub-agents and members
        agentStore.saveAgentInlineSpecs(id, TargetType.SUBAGENT, request.customSubAgents.toInlineToolConfigs(id, TargetType.SUBAGENT), owner)
        agentStore.saveAgentInlineSpecs(id, TargetType.MEMBER, request.customMembers.toInlineToolConfigs(id, TargetType.MEMBER), owner)
        loadAgentDto(updated, id)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: String,
        @RequestParam(required = false) scope: String? = null
    ): Mono<Void> = mono {
        val owner = resolveWriteOwner(parseAssetScope(scope))
        agentStore.findById(id, owner)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        agentStore.delete(id, owner)
    }.then()

    @GetMapping("/{id}/tools")
    fun getTools(@PathVariable id: String): Mono<List<String>> = mono {
        val agent = agentStore.findById(id, currentOwners())
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        agentStore.getAgentToolNames(id, agent.userId)
    }

    @PutMapping("/{id}/tools")
    fun updateTools(
        @PathVariable id: String,
        @RequestBody request: AgentToolsRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<List<String>> = mono {
        val owner = resolveWriteOwner(parseAssetScope(scope))
        val agent = agentStore.findById(id, owner)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        if (agent.userId == AuthConstants.SYSTEM_USER_ID) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot modify built-in agent: $id")
        }
        rejectInvalidSkillTools(ResourceExistenceValidator.validateSkillTools(
            request.toolNames, agentStore.getAgentSkillNames(id, owner), skillLoaderNames = skillLoaderNames()
        ))
        agentStore.saveAgentTools(id, request.toolNames, owner)
        request.toolNames
    }

    @GetMapping("/{id}/configs")
    fun getConfigs(
        @PathVariable id: String,
        @RequestParam(required = false) targetType: String?
    ): Mono<List<AgentToolConfigDto>> = mono {
        val agent = agentStore.findById(id, currentOwners())
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        // Whitelist rows live in the bucket the resolved agent belongs to, not the caller's.
        val owner = agent.userId
        if (targetType != null) {
            val type = parseTargetType(targetType)
            agentStore.getAgentToolConfigs(id, type, owner).map { it.toDto() }
        } else {
            val toolConfigs = agentStore.getAgentToolConfigs(id, TargetType.TOOL, owner)
            val subAgentConfigs = agentStore.getAgentToolConfigs(id, TargetType.SUBAGENT, owner)
            val skillConfigs = agentStore.getAgentToolConfigs(id, TargetType.SKILL, owner)
            val mcpConfigs = agentStore.getAgentToolConfigs(id, TargetType.MCP, owner)
            val commandConfigs = agentStore.getAgentToolConfigs(id, TargetType.COMMAND, owner)
            (toolConfigs + subAgentConfigs + skillConfigs + mcpConfigs + commandConfigs).map { it.toDto() }
        }
    }

    @PutMapping("/{id}/configs")
    fun saveConfigs(
        @PathVariable id: String,
        @RequestBody request: AgentConfigsRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<List<AgentToolConfigDto>> = mono {
        val owner = resolveWriteOwner(parseAssetScope(scope))
        val agent = agentStore.findById(id, owner)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        if (agent.userId == AuthConstants.SYSTEM_USER_ID) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot modify built-in agent: $id")
        }
        val type = parseTargetType(request.targetType)
        when (type) {
            TargetType.TOOL -> rejectInvalidSkillTools(ResourceExistenceValidator.validateSkillTools(
                request.targetNames, agentStore.getAgentSkillNames(id, owner), skillLoaderNames = skillLoaderNames()
            ))
            TargetType.SKILL -> rejectInvalidSkillTools(ResourceExistenceValidator.validateSkillTools(
                agentStore.getAgentToolNames(id, owner), request.targetNames, skillLoaderNames = skillLoaderNames()
            ))
            else -> Unit
        }
        agentStore.saveAgentToolConfigs(id, type, request.targetNames, owner)
        agentStore.getAgentToolConfigs(id, type, owner).map { it.toDto() }
    }

    /**
     * Get team agent member IDs.
     */
    @GetMapping("/{id}/members")
    fun getMembers(@PathVariable id: String): Mono<List<String>> = mono {
        val agent = agentStore.findById(id, currentOwners())
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        agentStore.getAgentMemberIds(id, agent.userId)
    }

    /**
     * Save team agent member IDs (replaces existing member list).
     * Members must be existing non-TEAM agents.
     */
    @PutMapping("/{id}/members")
    fun saveMembers(
        @PathVariable id: String,
        @RequestBody request: AgentMembersRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<List<String>> = mono {
        val owner = resolveWriteOwner(parseAssetScope(scope))
        val agent = agentStore.findById(id, owner)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        if (agent.userId == AuthConstants.SYSTEM_USER_ID) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot modify built-in agent: $id")
        }
        validateTeamMembers(agent.agentType, request.memberIds, emptyList(), currentOwners())
        agentStore.saveAgentMembers(id, request.memberIds, owner)
        request.memberIds
    }

    /**
     * Validate Jinja2 template syntax.
     * Returns validation result with detailed errors if the template is invalid.
     */
    @PostMapping("/validate-template")
    fun validateTemplate(@RequestBody request: ValidateTemplateRequest): Mono<ValidateTemplateResponse> = mono {
        if (request.template.isBlank()) {
            return@mono ValidateTemplateResponse(valid = true)
        }
        val renderer = templateRenderer
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Template renderer not available")
        try {
            renderer.renderLiteralTemplate(request.template, emptyMap())
            ValidateTemplateResponse(valid = true)
        } catch (e: InvalidTemplateException) {
            ValidateTemplateResponse(
                valid = false,
                errors = e.errors.map { err ->
                    TemplateValidationError(
                        message = err.message,
                        lineNumber = err.lineNumber,
                        startPosition = err.startPosition,
                        fieldName = err.fieldName,
                        severity = err.severity
                    )
                }
            )
        }
    }

    /**
     * List all available tools.
     */
    @GetMapping("/tools")
    fun listAvailableTools(): Mono<List<ToolInfo>> = mono {
        toolRegistry.getAllTools()
    }

    /**
     * Lightweight DTO for list endpoints — no extra DB queries.
     * Full config details (tools, subagents, skills, MCP, commands) are only
     * loaded via the detail endpoint GET /api/agents/{id}.
     */
    private fun AgentDefinition.toLightDto(): AgentDto = AgentDto(
        id = this.id,
        name = this.name,
        agentType = this.agentType,
        agentContext = this.agentContext,
        description = this.description,
        promptTemplate = this.promptTemplate,
        inputSchema = this.inputSchema,
        outputSchema = this.outputSchema,
        maxIterations = this.maxIterations,
        maxSubAgentDepth = this.maxSubAgentDepth,
        color = this.color,
        enabled = this.enabled,
        instructionsEnabled = this.instructionsEnabled,
        toolFoldEnabled = this.toolFoldEnabled,
        toolFoldKeepRecentRuns = this.toolFoldKeepRecentRuns,
        thinkingHistoryEnabled = this.thinkingHistoryEnabled,
        builtin = this.userId == AuthConstants.SYSTEM_USER_ID,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt
    )

    private fun AgentDefinition.toDto(
        toolNames: List<String>? = null,
        subAgentIds: List<String>? = null,
        skillNames: List<String>? = null,
        mcpConfigs: List<McpBindingDto>? = null,
        commandNames: List<String>? = null,
        memberIds: List<String>? = null,
        customSubAgents: List<InlineAgentSpec>? = null,
        customMembers: List<InlineAgentSpec>? = null
    ): AgentDto = AgentDto(
        id = this.id,
        name = this.name,
        agentType = this.agentType,
        agentContext = this.agentContext,
        description = this.description,
        customInstructions = this.customInstructions,
        promptTemplate = this.promptTemplate,
        toolNames = toolNames ?: this.toolNames,
        subAgentIds = subAgentIds ?: emptyList(),
        skillNames = skillNames ?: emptyList(),
        mcpConfigs = mcpConfigs ?: emptyList(),
        commandNames = commandNames ?: emptyList(),
        memberIds = memberIds ?: emptyList(),
        customSubAgents = customSubAgents ?: emptyList(),
        customMembers = customMembers ?: emptyList(),
        maxIterations = this.maxIterations,
        maxSubAgentDepth = this.maxSubAgentDepth,
        color = this.color,
        enabled = this.enabled,
        instructionsEnabled = this.instructionsEnabled,
        toolFoldEnabled = this.toolFoldEnabled,
        toolFoldKeepRecentRuns = this.toolFoldKeepRecentRuns,
        thinkingHistoryEnabled = this.thinkingHistoryEnabled,
        inputSchema = this.inputSchema,
        outputSchema = this.outputSchema,
        outputSchemaMultiTurn = this.outputSchemaMultiTurn,
        builtin = this.userId == AuthConstants.SYSTEM_USER_ID,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt
    )

    private fun AgentToolConfig.toDto(): AgentToolConfigDto = AgentToolConfigDto(
        agentId = this.agentId,
        targetType = this.targetType.name,
        targetName = this.targetName,
        metadata = this.metadata
    )

    private fun parseTargetType(value: String): TargetType = try {
        TargetType.valueOf(value.uppercase())
    } catch (_: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid targetType: $value. Must be TOOL, SUBAGENT, SKILL, MCP, or COMMAND")
    }

    /**
     * Validate team member references:
     * - TEAM agents require at least one member; non-TEAM agents ignore memberIds.
     * - All memberIds must reference existing agents (user-owned or built-in).
     * - Members must be ALL or SUBAGENT type (PRIMARY/TEAM not allowed as members).
     */
    /**
     * Members are validated against the caller's whole visibility set, not the write bucket: the
     * runtime resolves them the same way, so a group team may reference a personal sub-agent and a
     * personal team may reference a shared one.
     */
    private suspend fun validateTeamMembers(
        agentType: AgentType,
        memberIds: List<String>,
        customMembers: List<InlineAgentSpec>,
        owners: Collection<String>
    ) {
        if (agentType != AgentType.TEAM) return
        if (memberIds.isEmpty() && customMembers.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "TEAM agent requires at least one member (memberIds or customMembers)")
        }
        for (memberId in memberIds) {
            val member = agentStore.findById(memberId, owners)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Member agent not found: $memberId")
            if (member.agentType != AgentType.ALL && member.agentType != AgentType.SUBAGENT) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Member '$memberId' is ${member.agentType} — only ALL or SUBAGENT agents can be team members"
                )
            }
        }
    }

    private fun rejectInvalidSkillTools(errors: List<ConfigValidationError>) {
        if (errors.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST, errors.joinToString("; ") { "${it.field}: ${it.message}" }
            )
        }
    }

    private suspend fun loadAgentDto(agent: AgentDefinition, id: String): AgentDto = coroutineScope {
        // Whitelist rows are stored under the bucket the agent itself belongs to, which for a shared
        // or built-in agent is not the caller's.
        val owner = agent.userId
        val toolsDeferred = async { agentStore.getAgentToolNames(id, owner) }
        val subAgentConfigsDeferred = async { agentStore.getAgentToolConfigs(id, TargetType.SUBAGENT, owner) }
        val skillNamesDeferred = async { agentStore.getAgentSkillNames(id, owner) }
        val mcpConfigsDeferred = async { agentStore.getAgentMcpConfigs(id, owner).toMcpBindingDtos() }
        val commandNamesDeferred = async { agentStore.getAgentCommandNames(id, owner) }
        val memberConfigsDeferred = async { agentStore.getAgentToolConfigs(id, TargetType.MEMBER, owner) }

        val subAgentConfigs = subAgentConfigsDeferred.await()
        val memberConfigs = memberConfigsDeferred.await()

        // Separate global ID references from inline custom specs
        val subAgentIds = subAgentConfigs.filter { !it.targetName.startsWith("inline:") }.map { it.targetName }
        val customSubAgents = subAgentConfigs.filter { it.targetName.startsWith("inline:") }.mapNotNull { it.toInlineAgentSpec() }
        val memberIds = memberConfigs.filter { !it.targetName.startsWith("inline:") }.map { it.targetName }
        val customMembers = memberConfigs.filter { it.targetName.startsWith("inline:") }.mapNotNull { it.toInlineAgentSpec() }

        agent.toDto(
            toolNames = toolsDeferred.await(),
            subAgentIds = subAgentIds,
            skillNames = skillNamesDeferred.await(),
            mcpConfigs = mcpConfigsDeferred.await(),
            commandNames = commandNamesDeferred.await(),
            memberIds = memberIds,
            customSubAgents = customSubAgents,
            customMembers = customMembers
        )
    }

    private companion object {
        private const val MAX_DESCRIPTION_LENGTH = 200

        /** Clamp for [AgentCreateRequest.toolFoldKeepRecentRuns]; mirrors the frontend 0..5 range. */
        private const val MIN_TOOL_FOLD_KEEP_RECENT_RUNS = 0
        private const val MAX_TOOL_FOLD_KEEP_RECENT_RUNS = 5
        private val objectMapper = SharedObjectMapper.instance

        /** Convert AgentToolConfig list (targetType=MCP) to McpBindingDto list.
         *  Reads both legacy array format `["tool1"]` and new object format `{"toolNames":[...],"promptNames":[...]}`. */
        fun List<AgentToolConfig>.toMcpBindingDtos(): List<McpBindingDto> = map { config ->
            val (toolNames, promptNames) = config.metadata?.let { meta ->
                try {
                    val node = objectMapper.readTree(meta)
                    if (node.isArray) {
                        val names = mutableListOf<String>()
                        for (el in node) { names.add(el.asString()) }
                        names to emptyList()
                    } else {
                        val tools = mutableListOf<String>()
                        val toolNode = node.get("toolNames")
                        if (toolNode != null && toolNode.isArray) { for (el in toolNode) { tools.add(el.asString()) } }
                        val prompts = mutableListOf<String>()
                        val promptNode = node.get("promptNames")
                        if (promptNode != null && promptNode.isArray) { for (el in promptNode) { prompts.add(el.asString()) } }
                        tools to prompts
                    }
                } catch (_: Exception) {
                    emptyList<String>() to emptyList<String>()
                }
            } ?: (emptyList<String>() to emptyList<String>())
            McpBindingDto(serverName = config.targetName, toolNames = toolNames, promptNames = promptNames)
        }

        /** Convert McpBindingDto list to AgentToolConfig list.
         *  Always writes new object format; legacy array format is only read, never written. */
        fun List<McpBindingDto>.toAgentToolConfigs(agentId: String): List<AgentToolConfig> = map { binding ->
            val metadataObj = mutableMapOf<String, List<String>>()
            if (binding.toolNames.isNotEmpty()) metadataObj["toolNames"] = binding.toolNames
            if (binding.promptNames.isNotEmpty()) metadataObj["promptNames"] = binding.promptNames
            val metadata = metadataObj.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) }
            AgentToolConfig(
                id = "${agentId}_mcp_${binding.serverName}",
                agentId = agentId,
                targetType = TargetType.MCP,
                targetName = binding.serverName,
                metadata = metadata
            )
        }

        /** Convert InlineAgentSpec list to AgentToolConfig list for DB storage. */
        fun List<InlineAgentSpec>.toInlineToolConfigs(agentId: String, targetType: TargetType): List<AgentToolConfig> = map { spec ->
            AgentToolConfig(
                id = "${agentId}_inline_${spec.name}",
                agentId = agentId,
                targetType = targetType,
                targetName = "inline:${spec.name}",
                metadata = objectMapper.writeValueAsString(spec)
            )
        }

        /** Parse an AgentToolConfig with inline metadata back to InlineAgentSpec. */
        fun AgentToolConfig.toInlineAgentSpec(): InlineAgentSpec? {
            val json = metadata ?: return null
            return try {
                objectMapper.readValue(json, InlineAgentSpec::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
}
