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
 * Authorization is three-layered, and this tool holds the last two: the agent whitelist, then the
 * catalog gate. The registry is process-wide and keyed by (name, granularity), so without [catalog]
 * a user could type a name that another user owns and receive that user's files; [catalog] resolves
 * the requesting user's own row and refuses anything that is not theirs. A null catalog (no R2DBC)
 * keeps the previous whitelist-only behaviour.
 *
 * [config] feeds the granularity resolution of the catalog gate ([SkillOwnership.checkLoad]) so it
 * walks exactly the same candidate-root sequence the registry used to pick [registry]'s entry.
 */
class SkillTool(
    metadata: ToolMetadata,
    private val registry: SkillRegistry,
    private val allowedSkillNames: List<String> = emptyList(),
    private val catalog: AsyncSkillCatalogStore? = null,
    private val config: SkillConfig = SkillConfig(),
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
            val allowed = allowedSkillNames.joinToString(", ")
            return ToolResult(
                content = listOf(TextContent("Error: Skill '$skillName' is not authorized for this agent. Allowed skills: $allowed")),
                isError = true,
            )
        }

        val projectPath = agentContext.projectPath
        val skill = registry.get(skillName, projectPath)
        if (skill == null) {
            // Only advertise whitelisted skills that actually exist: the caller already knows the
            // whitelist from the previous branch, so this hint narrows to what is loadable right now.
            val available = registry.visibleFor(projectPath)
                .filter { it.name in allowedSkillNames }
                .joinToString(", ") { it.name }
            return ToolResult(
                content = listOf(
                    TextContent(
                        "Error: Skill '$skillName' not found. Authorized skills currently registered: $available. " +
                            "If you just wrote its SKILL.md, call refresh_skills — a file on disk is not a " +
                            "loadable skill until that has run."
                    )
                ),
                isError = true,
            )
        }

        // Catalog gate: the requesting user must own an enabled row for this name at this granularity.
        when (val permission = SkillOwnership.checkLoad(catalog, skillName, agentContext.userId, projectPath, config)) {
            is SkillLoadPermission.Allowed -> {
                // The catalog row is authoritative for *which* directory this request may serve —
                // reject whenever the registry location drifts from it, so a stale registry can never
                // hand over another tenant's or another project's bytes.
                val expected = SkillPaths.canonicalizeOrNull(permission.installPath)
                val actual = skill.location.parent?.let { SkillPaths.canonicalize(it) }
                if (expected != null && actual != expected) {
                    logger.warn(
                        "Rejected load of '{}': registry location '{}' does not match catalog installPath '{}' (cross-tenant collision or stale registry)",
                        skillName, actual, expected
                    )
                    return ToolResult(
                        content = listOf(
                            TextContent(
                                "Error: Skill '$skillName' installation is out of sync with the catalog. " +
                                    "Call refresh_skills to re-read the skill directories."
                            )
                        ),
                        isError = true,
                    )
                }
            }
            SkillLoadPermission.NotInstalled -> {
                logger.warn("Rejected load of '{}': no catalog row for user '{}'", skillName, agentContext.userId)
                return ToolResult(
                    content = listOf(
                        TextContent(
                            "Error: Skill '$skillName' is not installed for this user. " +
                                "Run skill_search to see what you can load, or call refresh_skills " +
                                "if you wrote it in this chat."
                        )
                    ),
                    isError = true,
                )
            }
            SkillLoadPermission.Disabled -> return ToolResult(
                content = listOf(
                    TextContent(
                        "Error: Skill '$skillName' is disabled. Enable it first " +
                            "(PATCH /api/skills/enabled {name: '$skillName', enabled: true})."
                    )
                ),
                isError = true,
            )
        }

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