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
            scriptLlmSegment = if (context.scriptLlmAvailable && includeTools) SCRIPT_LLM_SEGMENT else null,
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
            teamMembersList = context.teamMembers.takeIf { it.isNotEmpty() }?.let { list ->
                buildString {
                    appendLine("## Team Coordination Protocol")
                    appendLine("You are a team leader coordinating member agents to complete tasks.")
                    appendLine()
                    appendLine("### Your Members")
                    list.forEach { m ->
                        append("- `${m["id"]}`")
                        val name = m["name"] as? String
                        if (!name.isNullOrBlank() && name != m["id"]) append(" ($name)")
                        val desc = m["description"] as? String
                        if (!desc.isNullOrBlank()) append(": $desc")
                        appendLine()
                    }
                    appendLine()
                    appendLine("### Workflow")
                    appendLine("1. Analyze the task and plan which members to delegate to")
                    appendLine("2. Use `delegate_to_member` to assign work — it returns IMMEDIATELY while the member runs in the background")
                    appendLine("3. Use `wait_for_member_events` to block until members complete or report issues")
                    appendLine("4. React to events:")
                    appendLine("   - COMPLETED: check if all members are done")
                    appendLine("   - BLOCKED: use `resume_member` with the answer/guidance, or delegate the work to another member")
                    appendLine("   - ERROR: retry with a clearer task, reassign to another member, or report to the user")
                    appendLine("5. Repeat steps 3-4 until all members complete")
                    appendLine("6. Synthesize all member results into a comprehensive final response")
                    appendLine()
                    appendLine("### Important Rules")
                    appendLine("- You coordinate; members do the actual work. Do NOT execute tasks yourself.")
                    appendLine("- Delegate independent work to multiple members in parallel for efficiency.")
                    appendLine("- Always call `wait_for_member_events` after delegating — never end your turn while members are running.")
                    context.teamStatusSummary?.let { status ->
                        appendLine()
                        appendLine(status.trimEnd())
                        appendLine()
                        appendLine("Use this recovered status to decide: resume BLOCKED members via `resume_member`, re-delegate interrupted RUNNING members.")
                    }
                }
            },
            instructionsSegment = InstructionsLoader.formatForPrompt(context.instructions),
            outputSchemaSegment = if (context.outputSchema != null && !context.outputSchemaMultiTurn) {
                // Single-turn mode: include schema guidance in system prompt (existing behavior)
                buildString {
                    appendLine("## Output Format")
                    appendLine("Your final response MUST be a valid JSON object matching this schema:")
                    appendLine("```json")
                    appendLine(context.outputSchema)
                    appendLine("```")
                }
            } else {
                // Multi-turn mode: no schema in system prompt (deferred to completion check phase)
                null
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
        if (promptTemplate != null && promptTemplate.isBlank()) {
            return ""  // Preserve contract: blank = no system prompt
        }
        val base = when {
            promptTemplate == null ->
                defaultBuilder.build(buildDefaultConfig(context)).joinToString("\n\n")
            else -> try {
                renderer.renderLiteralTemplate(promptTemplate, context.toModel())
            } catch (e: Exception) {
                logger.error("Failed to render prompt template, falling back to default: {}", e.message, e)
                defaultBuilder.build(buildDefaultConfig(context, includeTools = false)).joinToString("\n\n")
            }
        }
        // Unconditionally append session variables (regardless of default or custom template)
        val varsSegment = buildSessionVariablesSegment(context.sessionVariables)
        // Append time-access guidance when the calc tool is available.
        // The segment is static text, keeping the system prompt prefix stable for LLM caching.
        val timeAccessSegment = if (context.tools.any { it["name"] == "calc" }) TIME_ACCESS_SEGMENT else null
        // Append memory guidance when the memory system is enabled. Static text for cache stability;
        // actual memory retrieval happens on demand via memory_search / memory_read tool calls.
        val memoryGuidanceSegment = if (context.memoryAvailable) MEMORY_GUIDANCE_SEGMENT else null
        // Append knowledge guidance when the knowledge base is enabled. Static text for cache stability;
        // actual retrieval happens on demand via knowledge_search / knowledge_read tool calls.
        val knowledgeGuidanceSegment = if (context.knowledgeAvailable) KNOWLEDGE_GUIDANCE_SEGMENT else null
        return listOfNotNull(base.takeIf { it.isNotBlank() }, varsSegment, timeAccessSegment, memoryGuidanceSegment, knowledgeGuidanceSegment)
            .joinToString("\n\n")
    }

    private fun buildSessionVariablesSegment(vars: Map<String, String>): String? {
        if (vars.isEmpty()) return null
        return buildString {
            appendLine("## Session Variables")
            appendLine("The following data was extracted during context compaction and persists across compaction rounds.")
            appendLine("IMPORTANT: When you need data that might have been discussed earlier, ALWAYS check")
            appendLine("this list first before relying on your memory of the conversation. This ensures")
            appendLine("data consistency throughout the session.")
            appendLine("Use these values as authoritative — do NOT fabricate or approximate them.")
            appendLine("For variables marked [file: path], use the read tool to load the full content.")
            vars.forEach { (k, v) -> appendLine("- $k: ${v.replace("\n", "\\n")}") }
        }
    }

    companion object {
        private val SCRIPT_LLM_SEGMENT = $$"""
## Script LLM Access

When executing scripts via the bash tool, the following environment variables are automatically available for LLM access:
- `EASYAI_SCRIPT_TOKEN`: Bearer token for the internal LLM API (auto-injected, 30min expiry)
- `EASYAI_BACKEND_URL`: Backend base URL (auto-injected)
- `EASYAI_MODEL_CONFIG_ID`: Default model configuration ID (auto-injected)

Scripts can call the LLM synchronously via HTTP:

```bash
curl -s -X POST "$EASYAI_BACKEND_URL/api/internal/llm/process" \
  -H "Authorization: Bearer $EASYAI_SCRIPT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"system","content":"instruction"},{"role":"user","content":"content to process"}]}'
```

For batch processing multiple items:
```bash
curl -s -X POST "$EASYAI_BACKEND_URL/api/internal/llm/batch-process" \
  -H "Authorization: Bearer $EASYAI_SCRIPT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"instruction":"system prompt","items":[{"id":"1","content":"text1"},{"id":"2","content":"text2"}]}'
```

Use this when you need to process many files with LLM (transcription, summarization, translation, etc.) via generated scripts. The script does NOT need any API key — authentication is handled by the injected token.
        """.trimIndent()

        /** Static guidance for on-demand time access via the calc tool (cache-stable). */
        private val TIME_ACCESS_SEGMENT = """
## Current Time

The current date and time is NOT included in this prompt to keep it stable for caching.
When you need the current date, time, or timezone (e.g. to report today's date, reason about deadlines, or compute time differences), use the `calc` tool with a script such as:
ZonedDateTime.now().toString()

This returns the current timestamp with the system's local timezone, e.g. 2026-08-09T10:30:45+08:00[Asia/Shanghai].
        """.trimIndent()

        /** Static guidance for on-demand memory retrieval via memory_* tools (cache-stable). */
        private val MEMORY_GUIDANCE_SEGMENT = """
## Memory

You have access to a persistent memory system via the memory_* tools. Memory content is NOT
included in this prompt to keep it stable for caching — retrieve it on demand instead.

At the START of each new task, proactively call `memory_search` with keywords extracted from
the user's request to recall relevant context: user preferences, past decisions, project
conventions, and prior conclusions. When `knowledge_search` is also available, issue it in the
SAME response as `memory_search` so both run in parallel. Use `memory_read` to load the full
content of a specific entry, and `memory_write` to persist durable facts worth remembering
across sessions.
        """.trimIndent()

        /** Static guidance for on-demand knowledge retrieval via knowledge_* tools (cache-stable). */
        private val KNOWLEDGE_GUIDANCE_SEGMENT = """
## Knowledge Base

You have access to a knowledge base via the knowledge_* tools. Knowledge content is NOT
included in this prompt to keep it stable for caching — retrieve it on demand instead.

At the START of each new task, proactively call `knowledge_search` with keywords extracted
from the user's request to retrieve relevant documents. When `memory_search` is also
available, you MUST issue both calls in the SAME response so they run in parallel.
Use `knowledge_read` to load the full content of a specific entry by its key.
        """.trimIndent()
    }
}
