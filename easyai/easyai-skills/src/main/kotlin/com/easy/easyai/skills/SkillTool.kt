package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.io.path.walk

/**
 * Tool that allows the LLM agent to load a skill by name during conversation.
 * Returns the skill's content + a sampled list of associated files.
 */
class SkillTool(
    metadata: ToolMetadata,
    private val registry: SkillRegistry,
    private val allowedSkillNames: List<String> = emptyList(),
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun parameterType() = SkillToolParams::class.java
    override val executionMode = ToolExecutionMode.SEQUENTIAL

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit,
    ): ToolResult {
        val skillName = args["name"] as? String ?: ""

        if (skillName.isBlank()) {
            return ToolResult(
                content = listOf(TextContent("Error: 'name' parameter is required. Provide a valid skill name.")),
                isError = true,
            )
        }

        val skill = registry.get(skillName)
        if (skill == null) {
            val available = registry.all().joinToString(", ") { it.name }
            return ToolResult(
                content = listOf(TextContent("Error: Skill '$skillName' not found. Available skills: $available")),
                isError = true,
            )
        }

        // Whitelist filtering: empty allowedSkillNames means no skills allowed (consistent with toolNames)
        if (skillName !in allowedSkillNames) {
            if (allowedSkillNames.isEmpty()) {
                return ToolResult(
                    content = listOf(TextContent("Error: No skills are authorized for this agent.")),
                    isError = true,
                )
            }
            val allowed = allowedSkillNames.joinToString(", ")
            return ToolResult(
                content = listOf(TextContent("Error: Skill '$skillName' is not authorized for this agent. Allowed skills: $allowed")),
                isError = true,
            )
        }

        // Sample files in the skill's directory
        val skillDir = skill.location.parent
        val sampleFiles = try {
            skillDir?.walk()
                ?.filter { Files.isRegularFile(it) && !it.toString().endsWith("SKILL.md") }
                ?.take(10)
                ?.map { it.toAbsolutePath().toString() }
                ?.toList()
                ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to list files in skill directory: {}", e.message)
            emptyList()
        }

        val baseDir = skill.location.parent
        val sb = StringBuilder()
        sb.append("<skill_content name=\"${skill.name}\">\n")
        sb.append("# Skill: ${skill.name}\n\n")
        if (!skill.description.isNullOrBlank()) {
            sb.append("**Description**: ${skill.description}\n\n")
        }
        if (skill.tags.isNotEmpty()) {
            sb.append("**Tags**: ${skill.tags.joinToString(", ")}\n\n")
        }
        sb.append("---\n\n")
        sb.append(skill.content)
        sb.append("\n\n")
        if (baseDir != null) {
            sb.append("Base directory for this skill: file://${baseDir.toAbsolutePath()}\n")
            sb.append("Relative paths in this skill are relative to this base directory.\n\n")
        }
        if (sampleFiles.isNotEmpty()) {
            sb.append("<skill_files>\n")
            sampleFiles.forEach { sb.append("<file>$it</file>\n") }
            sb.append("</skill_files>\n")
        }
        sb.append("</skill_content>")

        logger.info("Skill '{}' loaded successfully", skillName)
        return ToolResult(content = listOf(TextContent(sb.toString())))
    }
}

data class SkillToolParams(val name: String)