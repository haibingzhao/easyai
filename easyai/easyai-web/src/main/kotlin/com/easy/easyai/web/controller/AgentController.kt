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
import com.easy.easyai.web.model.ValidateTemplateRequest
import com.easy.easyai.web.model.ValidateTemplateResponse
import com.easy.easyai.web.model.TemplateValidationError
import com.easy.easyai.web.security.getCurrentUserId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.mono
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
) {

    @GetMapping
    fun listAll(): Mono<List<AgentDto>> = mono {
        val userId = getCurrentUserId()
        agentStore.findAll(userId).map { it.toLightDto() }
    }

    @GetMapping("/subagents")
    fun listSubAgents(): Mono<List<AgentDto>> = mono {
        val userId = getCurrentUserId()
        agentStore.findSubAgents(userId).map { it.toLightDto() }
    }

    @GetMapping("/chat")
    fun listChatAgents(): Mono<List<AgentDto>> = mono {
        val userId = getCurrentUserId()
        agentStore.findChatAgents(userId).map { it.toLightDto() }
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): Mono<AgentDto> = mono {
        val userId = getCurrentUserId()
        val agent = agentStore.findById(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        loadAgentDto(agent, id)
    }

    /**
     * Export an agent configuration as a self-contained JSON file.
     */
    @GetMapping("/{id}/export")
    fun exportAgent(@PathVariable id: String): Mono<ResponseEntity<String>> = mono {
        val userId = getCurrentUserId()
        val agent = agentStore.findById(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        val dto = loadAgentDto(agent, id)
        val exportDto = mapOf("formatVersion" to 1, "agent" to dto)
        val json = objectMapper.writeValueAsString(exportDto)
        val safeId = id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"${safeId}.agent.json\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(json)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: AgentCreateRequest): Mono<AgentDto> = mono {
        require((request.description?.length ?: 0) <= MAX_DESCRIPTION_LENGTH) {
            "Description must be $MAX_DESCRIPTION_LENGTH characters or less"
        }
        val userId = getCurrentUserId()
        // Check conflict with built-in system agent first (clearer error message)
        val systemAgent = agentStore.findById(request.id, AuthConstants.SYSTEM_USER_ID)
        if (systemAgent != null && systemAgent.userId == AuthConstants.SYSTEM_USER_ID) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Agent ID '${request.id}' is reserved by a built-in system agent. Please choose a different ID."
            )
        }
        // Check if current user already has an agent with this ID
        val existing = agentStore.findById(request.id, userId)
        if (existing != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Agent already exists: ${request.id}")
        }
        validateTeamMembers(request.agentType, request.memberIds, userId)
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
            inputSchema = request.inputSchema,
            outputSchema = request.outputSchema
        )
        agentStore.save(agent, userId)
        // Persist tool whitelist and sub-agent associations (always, even if empty)
        agentStore.saveAgentToolConfigs(request.id, TargetType.TOOL, request.toolNames)
        agentStore.saveAgentToolConfigs(request.id, TargetType.SUBAGENT, request.subAgentIds)
        agentStore.saveAgentToolConfigs(request.id, TargetType.SKILL, request.skillNames)
        agentStore.saveAgentMcpConfigs(request.id, request.mcpConfigs.toAgentToolConfigs(request.id))
        agentStore.saveAgentCommands(request.id, request.commandNames)
        agentStore.saveAgentMembers(request.id, request.memberIds)
        agent.toDto(
            toolNames = request.toolNames,
            subAgentIds = request.subAgentIds,
            skillNames = request.skillNames,
            mcpConfigs = request.mcpConfigs,
            commandNames = request.commandNames,
            memberIds = request.memberIds
        )
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody request: AgentCreateRequest): Mono<AgentDto> = mono {
        require((request.description?.length ?: 0) <= MAX_DESCRIPTION_LENGTH) {
            "Description must be $MAX_DESCRIPTION_LENGTH characters or less"
        }
        val userId = getCurrentUserId()
        val existing = agentStore.findById(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")

        if (existing.userId == AuthConstants.SYSTEM_USER_ID) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot modify built-in agent: $id")
        }
        validateTeamMembers(request.agentType, request.memberIds, userId)

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
            inputSchema = request.inputSchema,
            outputSchema = request.outputSchema,
            updatedAt = java.time.Instant.now().epochSecond
        )
        agentStore.update(updated, userId)
        // Persist tool whitelist and sub-agent associations
        agentStore.saveAgentToolConfigs(id, TargetType.TOOL, request.toolNames)
        agentStore.saveAgentToolConfigs(id, TargetType.SUBAGENT, request.subAgentIds)
        agentStore.saveAgentToolConfigs(id, TargetType.SKILL, request.skillNames)
        agentStore.saveAgentMcpConfigs(id, request.mcpConfigs.toAgentToolConfigs(id))
        agentStore.saveAgentCommands(id, request.commandNames)
        agentStore.saveAgentMembers(id, request.memberIds)
        loadAgentDto(updated, id)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String): Mono<Void> = mono {
        val userId = getCurrentUserId()
        agentStore.findById(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        agentStore.delete(id, userId)
    }.then()

    @GetMapping("/{id}/tools")
    fun getTools(@PathVariable id: String): Mono<List<String>> = mono {
        val userId = getCurrentUserId()
        agentStore.findById(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        agentStore.getAgentToolNames(id)
    }

    @PutMapping("/{id}/tools")
    fun updateTools(@PathVariable id: String, @RequestBody request: AgentToolsRequest): Mono<List<String>> = mono {
        val userId = getCurrentUserId()
        val agent = agentStore.findById(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        if (agent.userId == AuthConstants.SYSTEM_USER_ID) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot modify built-in agent: $id")
        }
        agentStore.saveAgentTools(id, request.toolNames)
        request.toolNames
    }

    @GetMapping("/{id}/configs")
    fun getConfigs(
        @PathVariable id: String,
        @RequestParam(required = false) targetType: String?
    ): Mono<List<AgentToolConfigDto>> = mono {
        val userId = getCurrentUserId()
        agentStore.findById(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        if (targetType != null) {
            val type = parseTargetType(targetType)
            agentStore.getAgentToolConfigs(id, type).map { it.toDto() }
        } else {
            val toolConfigs = agentStore.getAgentToolConfigs(id, TargetType.TOOL)
            val subAgentConfigs = agentStore.getAgentToolConfigs(id, TargetType.SUBAGENT)
            val skillConfigs = agentStore.getAgentToolConfigs(id, TargetType.SKILL)
            val mcpConfigs = agentStore.getAgentToolConfigs(id, TargetType.MCP)
            val commandConfigs = agentStore.getAgentToolConfigs(id, TargetType.COMMAND)
            (toolConfigs + subAgentConfigs + skillConfigs + mcpConfigs + commandConfigs).map { it.toDto() }
        }
    }

    @PutMapping("/{id}/configs")
    fun saveConfigs(
        @PathVariable id: String,
        @RequestBody request: AgentConfigsRequest
    ): Mono<List<AgentToolConfigDto>> = mono {
        val userId = getCurrentUserId()
        val agent = agentStore.findById(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        if (agent.userId == AuthConstants.SYSTEM_USER_ID) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot modify built-in agent: $id")
        }
        val type = parseTargetType(request.targetType)
        agentStore.saveAgentToolConfigs(id, type, request.targetNames)
        agentStore.getAgentToolConfigs(id, type).map { it.toDto() }
    }

    /**
     * Get team agent member IDs.
     */
    @GetMapping("/{id}/members")
    fun getMembers(@PathVariable id: String): Mono<List<String>> = mono {
        val userId = getCurrentUserId()
        agentStore.findById(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        agentStore.getAgentMemberIds(id)
    }

    /**
     * Save team agent member IDs (replaces existing member list).
     * Members must be existing non-TEAM agents.
     */
    @PutMapping("/{id}/members")
    fun saveMembers(
        @PathVariable id: String,
        @RequestBody request: AgentMembersRequest
    ): Mono<List<String>> = mono {
        val userId = getCurrentUserId()
        val agent = agentStore.findById(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: $id")
        if (agent.userId == AuthConstants.SYSTEM_USER_ID) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot modify built-in agent: $id")
        }
        validateTeamMembers(agent.agentType, request.memberIds, userId)
        agentStore.saveAgentMembers(id, request.memberIds)
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
        memberIds: List<String>? = null
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
        maxIterations = this.maxIterations,
        maxSubAgentDepth = this.maxSubAgentDepth,
        color = this.color,
        enabled = this.enabled,
        instructionsEnabled = this.instructionsEnabled,
        inputSchema = this.inputSchema,
        outputSchema = this.outputSchema,
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
     * - Members cannot be TEAM type (prevents infinite nesting); only PRIMARY/SUBAGENT/ALL.
     */
    private suspend fun validateTeamMembers(agentType: AgentType, memberIds: List<String>, userId: String) {
        if (agentType != AgentType.TEAM) return
        if (memberIds.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "TEAM agent requires at least one member (memberIds)")
        }
        for (memberId in memberIds) {
            val member = agentStore.findById(memberId, userId)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Member agent not found: $memberId")
            if (member.agentType == AgentType.TEAM) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Member '$memberId' is a TEAM agent — nested teams are not allowed"
                )
            }
        }
    }

    private suspend fun loadAgentDto(agent: AgentDefinition, id: String): AgentDto = coroutineScope {
        val toolsDeferred = async { agentStore.getAgentToolNames(id) }
        val subAgentIdsDeferred = async { agentStore.getAgentSubAgentNames(id) }
        val skillNamesDeferred = async { agentStore.getAgentSkillNames(id) }
        val mcpConfigsDeferred = async { agentStore.getAgentMcpConfigs(id).toMcpBindingDtos() }
        val commandNamesDeferred = async { agentStore.getAgentCommandNames(id) }
        val memberIdsDeferred = async { agentStore.getAgentMemberIds(id) }
        agent.toDto(
            toolNames = toolsDeferred.await(),
            subAgentIds = subAgentIdsDeferred.await(),
            skillNames = skillNamesDeferred.await(),
            mcpConfigs = mcpConfigsDeferred.await(),
            commandNames = commandNamesDeferred.await(),
            memberIds = memberIdsDeferred.await()
        )
    }

    private companion object {
        private const val MAX_DESCRIPTION_LENGTH = 200
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
    }
}
