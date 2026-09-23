package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.tool.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * Tool that allows the LLM agent to load a skill by name during conversation.
 * Returns the skill's content + a sampled list of associated files.
 *
 * Resolves the same name-bound, enabled and whitelisted instance advertised by prompt and search.
 * Catalog failures never fall back to a registry-only read.
 */
class SkillTool(
    metadata: ToolMetadata,
    private val registry: SkillRegistry,
    private val allowedSkillNames: List<String> = emptyList(),
    private val catalog: AsyncSkillCatalogStore? = null,
    private val config: SkillConfig = SkillConfig(),
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val modelView = SkillModelView(registry, catalog, config)

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

        // Whitelist gate runs before any registry lookup: the error message must not leak skill
        // names the agent is not authorised to see (a restricted sub-agent's whitelist is a
        // security boundary, not a UI hint).
        if (skillName !in allowedSkillNames) {
            if (allowedSkillNames.isEmpty()) {
                return ToolResult(
                    content = listOf(TextContent("Error: No skills are authorized for this agent.")),
                    isError = true,
                )
            }
            return ToolResult(
                content = listOf(TextContent("Error: Skill '$skillName' is not authorized for this agent.")),
                isError = true,
            )
        }

        val visible = try {
            modelView.list(agentContext.userId, agentContext.projectPath, allowedSkillNames)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Skill access is unavailable: {}", e.message)
            return ToolResult(
                content = listOf(TextContent("Error: Skill catalog is unavailable; skill access could not be verified. Retry later.")),
                isError = true
            )
        }
        val skill = visible.firstOrNull { it.skill.name == skillName }?.skill
            ?: return ToolResult(
                content = listOf(TextContent(
                    "Error: Skill '$skillName' is not available for this agent in the current project " +
                        "(not installed, disabled, or out of sync). Call refresh_skills after writing a skill."
                )),
                isError = true
            )

        // Sample files in the skill's directory
        val skillDir = skill.location.parent
        val sampleFiles = listSampleFiles(skillDir)

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

    private companion object {
        /** Cap on the sample-file listing shown to the LLM; keeps the tool result bounded on large skills. */
        private const val SAMPLE_FILE_LIMIT = 10L
    }

    /**
     * Bounded, non-leaking listing of the files that sit next to SKILL.md.
     *
     * Uses `Files.walk(...).use { }` so the underlying FileTreeWalker is closed even when `limit`
     * short-circuits the stream — the previous `Path.walk().take(N)` sequence form left the walker
     * open until GC, which leaked file descriptors on every `load_skill` call.
     */
    private fun listSampleFiles(skillDir: Path?): List<String> {
        if (skillDir == null) return emptyList()
        return try {
            Files.walk(skillDir).use { stream ->
                stream.asSequence()
                    .filter { Files.isRegularFile(it) && it.fileName.toString() != "SKILL.md" }
                    .take(SAMPLE_FILE_LIMIT.toInt())
                    .map { it.toAbsolutePath().toString() }
                    .toList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to list files in skill directory: {}", e.message)
            emptyList()
        }
    }
}

data class SkillToolParams(val name: String)