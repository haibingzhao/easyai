package com.easy.easyai.core.prompt

import com.easy.easyai.common.textio.template.TemplateRenderer
import org.slf4j.LoggerFactory

/**
 * Renders system prompts using Jinja2 templates stored in the agent definition.
 *
 * When [com.easy.easyai.core.agent.AgentDefinition.promptTemplate] is non-null,
 * this service renders it with the provided [PromptContext].
 * When null, falls back to the default [SystemPromptBuilder] logic.
 */
class PromptTemplateService(
    private val renderer: TemplateRenderer,
    private val defaultBuilder: SystemPromptBuilder
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private fun StringBuilder.appendItemLine(item: Map<String, Any?>) {
        append("- `${item["name"]}`")
        val desc = (item["description"] as? String)?.takeIf { it.isNotBlank() }
        if (desc != null) append(": $desc")
        appendLine()
    }

    private fun formatItemList(items: List<Map<String, Any?>>): String = buildString {
        items.forEach { appendItemLine(it) }
    }

    private fun buildDefaultConfig(context: PromptContext, includeTools: Boolean = true): AgentPromptConfig {
        return AgentPromptConfig(
            protocol = context.protocol,
            customInstructions = context.customInstructions,
            cwd = if (includeTools) context.cwd else null,
            os = if (includeTools) context.os else null,
            toolsList = if (includeTools) context.tools.takeIf { it.isNotEmpty() }?.let { list ->
                buildString {
                    appendLine("## Available Tools")
                    appendLine("You have access to the following tools:")
                    append(formatItemList(list))
                }
            } else null,
            skillsList = if (includeTools) context.skills.takeIf { it.isNotEmpty() }?.let { list ->
                buildString {
                    appendLine("Skills provide specialized instructions and workflows for specific tasks.")
                    appendLine("Use the `load_skill` tool to load a skill when a task matches its description.")
                    append(formatItemList(list))
                }
            } else null,
            subAgentsList = if (includeTools) context.subAgents.takeIf { it.isNotEmpty() }?.let { list ->
                buildString {
                    appendLine("## Available Sub-Agents")
                    appendLine("You can delegate tasks to specialized sub-agents using the `task` tool.")
                    list.forEach { sa ->
                        appendItemLine(sa)
                        val schema = sa["inputSchema"] as? String
                        if (!schema.isNullOrBlank()) {
                            appendLine("  Input Schema (provide matching JSON in `inputData`):")
                            appendLine("  ```json")
                            appendLine("  $schema")
                            appendLine("  ```")
                        }
                    }
                    appendLine("When delegating, provide a complete prompt with all necessary context.")
                    appendLine("If the sub-agent defines an input schema, you MUST provide matching `inputData`.")
                    appendLine("The subagent result will be returned to you as the tool output.")
                }
            } else null,
            instructionsSegment = InstructionsLoader.formatForPrompt(context.instructions),
            memorySegment = context.memory,
            outputSchemaSegment = context.outputSchema?.let { schema ->
                buildString {
                    appendLine("## Output Format")
                    appendLine("Your final response MUST be a valid JSON object matching this schema:")
                    appendLine("```json")
                    appendLine(schema)
                    appendLine("```")
                }
            }
        )
    }

    /**
     * Build the system prompt for an agent.
     *
     * @param promptTemplate null = use default [SystemPromptBuilder]; blank = no system prompt.
     * @param context The prompt context with all template variables.
     * @return The rendered system prompt string, or empty string when prompt is explicitly blank.
     */
    fun build(promptTemplate: String?, context: PromptContext): String {
        // null = use default SystemPromptBuilder; blank = no system prompt at all
        if (promptTemplate == null) {
            return defaultBuilder.build(buildDefaultConfig(context)).joinToString("\n\n")
        }
        if (promptTemplate.isBlank()) {
            return ""
        }

        return try {
            renderer.renderLiteralTemplate(promptTemplate, context.toModel())
        } catch (e: Exception) {
            logger.error("Failed to render prompt template, falling back to default: {}", e.message, e)
            defaultBuilder.build(buildDefaultConfig(context, includeTools = false)).joinToString("\n\n")
        }
    }
}
