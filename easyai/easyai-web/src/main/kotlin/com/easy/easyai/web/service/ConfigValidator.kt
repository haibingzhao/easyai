package com.easy.easyai.web.service

import com.easy.easyai.agent.api.model.AgentCreateRequest
import com.easy.easyai.common.textio.template.InvalidTemplateException
import com.easy.easyai.common.textio.template.TemplateRenderer
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.skills.SkillRegistry
import com.easy.easyai.swarm.dag.DagAlgorithms
import com.easy.easyai.swarm.model.DeliberationSpec
import com.easy.easyai.swarm.model.SwarmAgentSpec
import com.easy.easyai.swarm.model.SwarmTask
import com.easy.easyai.swarm.model.TaskType
import com.easy.easyai.tools.mcp.McpClientManager
import com.easy.easyai.web.model.ConfigValidationError
import com.easy.easyai.web.model.ConfigValidationResult
import com.easy.easyai.web.service.validation.AgentConfigValidator
import com.easy.easyai.web.service.validation.FieldConstraintValidator
import com.easy.easyai.web.service.validation.ResourceExistenceValidator
import com.easy.easyai.web.service.validation.TemplateConsistencyValidator
import com.easy.easyai.web.service.validation.TemplateSyntaxValidator
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * Validates generated (or user-supplied) Agent and Swarm configs.
 *
 * Agent validation is delegated to a chain of [AgentConfigValidator] implementations:
 * 1. JSON structure (Jackson deserialization)
 * 2. Field constraints (length, format) — [FieldConstraintValidator]
 * 3. Resource existence (tools, skills, MCP servers, agents) — [ResourceExistenceValidator]
 * 4. Template syntax (Jinja2) — [TemplateSyntaxValidator]
 * 5. Template-Config consistency — [TemplateConsistencyValidator]
 *
 * Swarm validation remains inline (different structure).
 */
class ConfigValidator(
    private val objectMapper: ObjectMapper,
    private val templateRenderer: TemplateRenderer? = null,
    private val agentStore: AsyncAgentStore? = null,
    private val agentValidators: List<AgentConfigValidator> = emptyList(),
) {
    private val logger = LoggerFactory.getLogger(ConfigValidator::class.java)

    /**
     * Validate an Agent config from a JsonNode.
     */
    suspend fun validateAgentConfig(configNode: JsonNode, userId: String): ConfigValidationResult {
        // 1. Deserialize
        val request = try {
            objectMapper.treeToValue(configNode, AgentCreateRequest::class.java)
        } catch (e: Exception) {
            return ConfigValidationResult(
                valid = false,
                errors = listOf(ConfigValidationError("config", "Invalid JSON structure: ${e.message}"))
            )
        }

        // 2. Delegate to chain of validators
        val allErrors = mutableListOf<ConfigValidationError>()
        if (agentValidators.isEmpty()) {
            logger.warn("No agent validators configured — agent config validation is effectively disabled")
        }
        for (validator in agentValidators) {
            try {
                allErrors.addAll(validator.validate(request, userId))
            } catch (e: Exception) {
                logger.warn("Validator {} threw exception: {}", validator::class.java.simpleName, e.message)
                allErrors.add(ConfigValidationError(
                    "validation",
                    "Internal validation error in ${validator::class.java.simpleName}: ${e.message}",
                    "error"
                ))
            }
        }

        return ConfigValidationResult(
            valid = allErrors.none { it.severity == "error" },
            errors = allErrors
        )
    }

    /**
     * Validate a Swarm preset config from a JsonNode.
     */
    suspend fun validateSwarmConfig(configNode: JsonNode, userId: String): ConfigValidationResult {
        val errors = mutableListOf<ConfigValidationError>()

        // 1. Parse basic fields
        val name = configNode.get("name")?.asText()
        if (name.isNullOrBlank()) {
            errors.add(ConfigValidationError("name", "Swarm preset name is required"))
        }
        val title = configNode.get("title")?.asText()
        if (title.isNullOrBlank()) {
            errors.add(ConfigValidationError("title", "Swarm preset title is required"))
        }

        // 2. Parse agents
        val agentsNode = configNode.get("agents")
        val agents = mutableListOf<SwarmAgentSpec>()
        if (agentsNode == null || !agentsNode.isArray || agentsNode.size() == 0) {
            errors.add(ConfigValidationError("agents", "At least one agent is required"))
        } else {
            for ((i, agentNode) in agentsNode.withIndex()) {
                try {
                    val agent = objectMapper.treeToValue(agentNode, SwarmAgentSpec::class.java)
                    agents.add(agent)
                } catch (e: Exception) {
                    errors.add(ConfigValidationError("agents[$i]", "Invalid agent spec: ${e.message}"))
                }
            }
        }

        // 3. Validate agent references exist in DB
        val agentIds = agents.map { it.id }.toSet()
        val store = agentStore
        if (store != null) {
            for (agent in agents) {
                val exists = store.findById(agent.agentDefinitionId, userId) != null
                if (!exists) {
                    errors.add(ConfigValidationError(
                        "agents", "Agent definition '${agent.agentDefinitionId}' does not exist"
                    ))
                }
            }
        } else {
            errors.add(ConfigValidationError(
                "agents", "Agent existence check skipped: agent store unavailable", "warning"
            ))
        }

        // 4. Parse tasks
        val tasksNode = configNode.get("tasks")
        val tasks = mutableListOf<SwarmTask>()
        if (tasksNode == null || !tasksNode.isArray || tasksNode.size() == 0) {
            errors.add(ConfigValidationError("tasks", "At least one task is required"))
        } else {
            for ((i, taskNode) in tasksNode.withIndex()) {
                try {
                    val task = objectMapper.treeToValue(taskNode, SwarmTask::class.java)
                    tasks.add(task)
                } catch (e: Exception) {
                    errors.add(ConfigValidationError("tasks[$i]", "Invalid task: ${e.message}"))
                }
            }
        }

        // 5. Validate task agent references
        val taskIds = tasks.map { it.id }.toSet()
        for (task in tasks) {
            if (task.type == TaskType.SINGLE && task.agentId.isNotBlank() && task.agentId !in agentIds) {
                errors.add(ConfigValidationError(
                    "tasks", "Task '${task.id}' references unknown agent '${task.agentId}'"
                ))
            }
            // Validate dependsOn references
            for (dep in task.dependsOn) {
                if (dep !in taskIds) {
                    errors.add(ConfigValidationError(
                        "tasks", "Task '${task.id}' depends on unknown task '$dep'"
                    ))
                }
            }
            // Validate deliberation references
            if (task.type == TaskType.DELIBERATION) {
                val delib = task.deliberation
                if (delib != null) {
                    validateDeliberation(delib, task.id, agentIds, errors)
                }
            }
        }

        // 6. DAG validation
        if (tasks.isNotEmpty()) {
            try {
                DagAlgorithms.validateDag(tasks)
            } catch (e: IllegalStateException) {
                errors.add(ConfigValidationError("tasks", "DAG validation failed: ${e.message}"))
            } catch (e: IllegalArgumentException) {
                errors.add(ConfigValidationError("tasks", "DAG validation failed: ${e.message}"))
            }
        }

        // 7. Jinja2 template validation for task prompts
        for (task in tasks) {
            validateJinja2(task.promptTemplate, "tasks.${task.id}.promptTemplate", errors)
            val delib = task.deliberation
            if (delib != null) {
                validateJinja2(delib.contextTemplate, "tasks.${task.id}.deliberation.contextTemplate", errors)
            }
            val team = task.team
            if (team != null) {
                validateJinja2(team.contextTemplate, "tasks.${task.id}.team.contextTemplate", errors)
            }
        }

        return ConfigValidationResult(
            valid = errors.none { it.severity == "error" },
            errors = errors
        )
    }

    private fun validateJinja2(template: String?, fieldName: String, errors: MutableList<ConfigValidationError>) {
        if (template.isNullOrBlank() || templateRenderer == null) return
        try {
            templateRenderer.renderLiteralTemplate(template, emptyMap())
        } catch (e: InvalidTemplateException) {
            val msg = e.errors.joinToString("; ") { it.message }
            errors.add(ConfigValidationError(fieldName, "Jinja2 template error: $msg"))
        } catch (e: Exception) {
            errors.add(ConfigValidationError(fieldName, "Jinja2 template error: ${e.message}"))
        }
    }

    private fun validateDeliberation(
        deliberation: DeliberationSpec,
        taskId: String,
        agentIds: Set<String>,
        errors: MutableList<ConfigValidationError>
    ) {
        for (participant in deliberation.participants) {
            if (participant !in agentIds) {
                errors.add(ConfigValidationError(
                    "tasks.$taskId.deliberation", "Participant '$participant' not found in agents"
                ))
            }
        }
        if (deliberation.judge !in agentIds) {
            errors.add(ConfigValidationError(
                "tasks.$taskId.deliberation", "Judge '${deliberation.judge}' not found in agents"
            ))
        }
    }

    companion object {
        /**
         * Backward-compatible constructor for Swarm validation and legacy callers.
         */
        @JvmStatic
        fun forLegacy(
            toolRegistry: com.easy.easyai.agent.registry.ToolRegistry,
            agentStore: AsyncAgentStore,
            objectMapper: ObjectMapper,
            skillRegistry: SkillRegistry? = null,
            mcpClientManager: McpClientManager? = null,
            templateRenderer: TemplateRenderer? = null,
        ): ConfigValidator {
            return ConfigValidator(
                objectMapper = objectMapper,
                templateRenderer = templateRenderer,
                agentStore = agentStore,
                agentValidators = listOf(
                    FieldConstraintValidator(),
                    ResourceExistenceValidator(
                        toolRegistry = toolRegistry,
                        agentStore = agentStore,
                        skillRegistry = skillRegistry,
                        mcpClientManager = mcpClientManager,
                    ),
                    TemplateSyntaxValidator(templateRenderer),
                    TemplateConsistencyValidator(),
                ),
            )
        }
    }
}
