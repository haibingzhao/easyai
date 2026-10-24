package com.easy.easyai.web.service.skillgen

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import com.easy.easyai.skills.SkillAccessResolver
import com.easy.easyai.skills.SkillConfig
import com.easy.easyai.skills.SkillPackageScanner
import com.easy.easyai.skills.SkillPaths
import com.easy.easyai.skills.SkillScanCategory
import com.easy.easyai.skills.SkillScanSeverity
import com.easy.easyai.skills.SkillUploadFile
import com.easy.easyai.tools.web.WebFetchParams
import com.easy.easyai.tools.web.WebFetchTool
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * Panel-level inline cap. The package travels through the model's tool arguments, the SSE preview
 * and the next turn's request body; the last one sits under WebFlux's default 256 KiB JSON body
 * limit once escaping and the conversation history are added.
 */
internal const val SKILL_INLINE_MAX_BYTES = 128L * 1024

private val SKILL_NAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]*$""")
private const val SKILL_NAME_MAX_LENGTH = 64

/** Path-safe skill name: the scanner's slug charset plus the directory-safety cap. */
internal fun isValidSkillName(name: String): Boolean =
    SKILL_NAME.matches(name) && name.length <= SKILL_NAME_MAX_LENGTH && SkillPaths.safeSegment(name) == name

internal fun targetLayer(writeOwner: String, userId: String): String = when {
    writeOwner == SkillCatalogEntry.DEFAULT_USER_ID -> "shared"
    writeOwner == userId -> "personal"
    else -> "group"
}

private fun layerOf(entryOwner: String?, userId: String): String = when {
    // No catalog row means no recorded bucket: a skill of a catalog-less deployment.
    entryOwner == null -> "disk"
    entryOwner == SkillCatalogEntry.DEFAULT_USER_ID -> "shared"
    entryOwner == userId -> "personal"
    else -> "group"
}

/**
 * The shared `webfetch` tool with its output capped for this panel's context budget.
 *
 * SSRF rejection, redirect policy and the 5 MB wire cap stay in [WebFetchTool]; only the text that
 * would enter the agent context is shortened.
 */
internal class BoundedWebFetchTool : BaseToolDefinition(METADATA) {

    override fun parameterType(): Class<*> = WebFetchParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val result = DELEGATE.execute(agentContext, toolCallId, messageId, args, coroutineScope, onUpdate)
        if (result.isError) return result
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        if (text.length <= MAX_OUTPUT_CHARS) return result
        return result.copy(
            content = listOf(
                TextContent(
                    text.take(MAX_OUTPUT_CHARS) +
                        "\n\n[truncated: the page was longer than $MAX_OUTPUT_CHARS characters; " +
                        "fetch a narrower URL or a raw file]"
                )
            )
        )
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 120_000

        /** One shared delegate: WebFetchTool owns a connection pool, one per request would leak it. */
        private val DELEGATE = WebFetchTool(
            ToolMetadata(
                name = "webfetch",
                description = "Fetch a URL",
                permissionCategory = "web",
                isDefaultTool = false
            )
        )

        private val METADATA = ToolMetadata(
            name = "webfetch",
            description = """
                Fetch a URL read-only. Use format="text" for raw files/JSON and format="markdown"
                for documentation pages. Private, loopback and internal addresses are rejected.
                Redirects are followed; if the final URL differs, the output starts with a
                [fetched <final-url> (redirected from <requested-url>)] line.
                Output is truncated, so fetch narrow raw files instead of whole sites.
            """.trimIndent(),
            permissionCategory = "skill",
            isDefaultTool = false
        )
    }
}

/** Lists what the caller can already see so the model avoids name collisions and shadowing. */
internal class ListInstalledSkillsTool(
    private val resolver: SkillAccessResolver?,
    private val writeOwner: String,
    private val userId: String,
    private val owners: Collection<String>
) : BaseToolDefinition(METADATA) {

    data class Parameters(
        @field:JsonPropertyDescription("No parameters; call with an empty object.")
        val note: String? = null
    )

    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val target = targetLayer(writeOwner, userId)
        val visible = try {
            resolver?.listScopedSkillsForOwners(owners).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult(
                content = listOf(
                    TextContent(
                        """
                        The skill catalog is unavailable, so installed-name and shadow checks were
                        skipped. The install target is the $target bucket, but conflicts and
                        shadowing cannot be verified right now.
                        """.trimIndent()
                    )
                )
            )
        }
        if (resolver == null) {
            return ToolResult(
                content = listOf(
                    TextContent(
                        "No skill catalog is wired in this deployment; installed-name checks are unavailable. " +
                            "The install target is the $target bucket."
                    )
                )
            )
        }

        val output = buildString {
            appendLine("Install target bucket: $target")
            appendLine(
                when (target) {
                    "shared" -> "Shared: every user sees it; only the system identity can write it."
                    "group" -> "Group: every group member sees it; only the group owner can change it."
                    else -> "Personal: visible only to you."
                }
            )
            appendLine()
            if (visible.isEmpty()) {
                appendLine("Visible skills: none")
            } else {
                appendLine("Visible skills:")
                visible.take(MAX_LISTED_SKILLS).forEach { scoped ->
                    val layer = layerOf(scoped.catalogEntry?.userId, userId)
                    appendLine("- [$layer] ${scoped.skill.name} — ${scoped.skill.description ?: "(no description)"}")
                }
                if (visible.size > MAX_LISTED_SKILLS) {
                    appendLine(
                        "- ... ${visible.size - MAX_LISTED_SKILLS} more not shown; the name checks below still cover them"
                    )
                }
            }
            val targetNames = visible
                .filter { it.catalogEntry?.userId == writeOwner }
                .map { it.skill.name }
            appendLine()
            appendLine(
                if (targetNames.isEmpty()) "Names already installed in the target bucket: none"
                else "Names already installed in the target bucket: ${targetNames.joinToString(", ")}"
            )
            // A row is what records the bucket. Without one the name cannot be attributed to a layer,
            // so calling it "visible from other layers" would invent a shadow that is not resolved.
            val unbound = visible.count { it.catalogEntry == null }
            if (unbound > 0) {
                appendLine(
                    "$unbound of them carry no catalog row (this deployment runs without the skill catalog), " +
                        "so their bucket is unresolved: treat every name above as possibly taken."
                )
            } else {
                val shadows = visible
                    .filter { it.catalogEntry != null && it.catalogEntry?.userId != writeOwner }
                    .map { it.skill.name }
                    .distinct()
                if (shadows.isNotEmpty()) {
                    appendLine(
                        "Names visible from other layers (a target-bucket copy shadows them): " +
                            shadows.joinToString(", ")
                    )
                }
            }
        }
        return ToolResult(content = listOf(TextContent(output)))
    }

    companion object {
        private const val MAX_LISTED_SKILLS = 100

        private val METADATA = ToolMetadata(
            name = "list_installed_skills",
            description = "List the skills visible to this user, the install target bucket, and names already taken. Call before choosing a name.",
            permissionCategory = "skill",
            isDefaultTool = false
        )
    }
}

/**
 * Validates and scans a prepared package, then returns it as a structured preview.
 *
 * This tool is the only writer-shaped boundary of the installer agent, and it writes nothing: the
 * package is scanned by [SkillPackageScanner] and surfaced through `ToolResult.details`; the user
 * installs it later through the existing upload endpoint. Blocking findings produce no preview.
 */
internal class SubmitSkillTool(
    private val skillConfig: SkillConfig?,
    private val catalog: AsyncSkillCatalogStore?,
    private val resolver: SkillAccessResolver?,
    private val writeOwner: String,
    private val userId: String,
    private val owners: Collection<String>
) : BaseToolDefinition(METADATA) {

    private val preflight = SkillProposalPreflight(skillConfig, catalog, resolver, writeOwner, owners)

    data class SubmitSkillFile(
        @field:JsonPropertyDescription("Path relative to the skill directory, e.g. SKILL.md or references/checklist.md")
        val path: String,
        @field:JsonPropertyDescription("Full UTF-8 content of the file")
        val content: String
    )

    data class Parameters(
        @field:JsonPropertyDescription("Slug name; must equal the frontmatter name and contain only letters, digits, dot, dash, underscore")
        val name: String,
        @field:JsonPropertyDescription("One paragraph describing the skill for the user")
        val summary: String,
        @field:JsonPropertyDescription("All files of the package, including SKILL.md at the root")
        val files: List<SubmitSkillFile>
    )

    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val name = (args["name"] as? String)?.trim().orEmpty()
        val summary = (args["summary"] as? String)?.trim().orEmpty()
        val files = filesOf(args["files"])

        if (!isValidSkillName(name)) {
            return errorResult(
                "INVALID_NAME: use a slug matching ${SKILL_NAME.pattern} with at most " +
                    "$SKILL_NAME_MAX_LENGTH characters, and make the frontmatter name identical."
            )
        }
        if (summary.isBlank()) return errorResult("INVALID_SUMMARY: provide a one-paragraph summary for the user")
        if (summary.length > MAX_SUMMARY_CHARS) {
            return errorResult("INVALID_SUMMARY: keep the summary under $MAX_SUMMARY_CHARS characters")
        }
        if (files == null) {
            return errorResult("INVALID_PACKAGE: every file needs a string path and string content; resend the whole package")
        }
        if (files.isEmpty()) return errorResult("INVALID_PACKAGE: submit at least SKILL.md")

        val totalBytes = files.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() }
        val cap = inlineCap()
        val report = SkillPackageScanner.scan(
            name,
            files.map { SkillUploadFile(it.path, it.content.toByteArray(Charsets.UTF_8)) },
            cap
        )

        if (report.blocking) {
            val blocked = report.findings.filter { it.blocking }
            val lines = blocked.joinToString("\n") { finding ->
                val location = listOfNotNull(finding.file, finding.line?.toString()).joinToString(":")
                "- [${finding.category.wire}] ${finding.id}${if (location.isBlank()) "" else " ($location)"}: " +
                    "${finding.message} Suggestion: ${finding.suggestion}"
            }
            return errorResult(
                "SCAN_BLOCKED: the package cannot be previewed until these are fixed:\n$lines\n" +
                    "Fix the package and call submit_skill again. Do not encode, split, or otherwise hide the flagged content."
            )
        }

        val checks = preflight.check(name)
        if (checks.conflicts.isNotEmpty()) {
            return errorResult("NAME_CONFLICT: ${checks.conflicts.joinToString(" ")}")
        }

        val target = targetLayer(writeOwner, userId)
        val highRisk = report.findings.filter {
            it.category == SkillScanCategory.SECURITY && it.severity == SkillScanSeverity.HIGH
        }
        val marker = if (report.requiresAcknowledgement) "HIGH_RISK_REVIEW" else "PROPOSAL_READY"
        val content = buildString {
            appendLine("$marker: skill '$name' is ready for review; it is NOT installed.")
            appendLine("Target bucket: $target. Files: ${files.size} ($totalBytes bytes).")
            if (checks.warnings.isNotEmpty()) {
                checks.warnings.forEach { appendLine("- warning: $it") }
            }
            if (highRisk.isNotEmpty()) {
                appendLine("High-risk findings the user must acknowledge:")
                highRisk.forEach { finding ->
                    val location = listOfNotNull(finding.file, finding.line?.toString()).joinToString(":")
                    appendLine("- [${finding.id}] ${if (location.isBlank()) "-" else location} ${finding.message}")
                }
                appendLine("Summarize these risks in one or two sentences and tell the user to confirm them in the card before installing.")
            } else {
                appendLine("The preview card is shown to the user; the user must click Install. Do not claim it is installed.")
            }
        }

        val proposal = buildMap<String, Any> {
            put("name", name)
            put("description", report.description ?: "")
            put("tags", report.tags.take(MAX_TAGS).map { it.take(MAX_TAG_CHARS) })
            put("version", report.version)
            put("summary", summary)
            put("files", files.map { file ->
                mapOf(
                    "path" to file.path,
                    "size" to file.content.toByteArray(Charsets.UTF_8).size,
                    "content" to file.content
                )
            })
            put("warnings", checks.warnings)
            put("target", target)
            put("scan", SkillScanPayload.of(report))
        }
        return ToolResult(
            content = listOf(TextContent(content)),
            details = mapOf("skillProposal" to proposal),
            terminate = !report.requiresAcknowledgement
        )
    }

    private fun inlineCap(): Long =
        (skillConfig?.packageMaxBytes ?: SKILL_INLINE_MAX_BYTES).coerceAtMost(SKILL_INLINE_MAX_BYTES)

    /** Null when any entry is malformed: silently dropping a file would scan and preview a package the model did not send. */
    private fun filesOf(raw: Any?): List<SubmitSkillFile>? {
        val list = raw as? List<*> ?: return emptyList()
        val parsed = list.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val path = map["path"] as? String ?: return@mapNotNull null
            val content = map["content"] as? String ?: return@mapNotNull null
            SubmitSkillFile(path, content)
        }
        return parsed.takeIf { it.size == list.size }
    }

    companion object {
        private const val MAX_SUMMARY_CHARS = 2_000
        private const val MAX_TAGS = 20
        private const val MAX_TAG_CHARS = 64

        private val METADATA = ToolMetadata(
            name = "submit_skill",
            description = """
                Validate and scan the prepared skill package and return a preview card. It never
                installs or writes files. On SCAN_BLOCKED, fix the listed package and call again;
                on HIGH_RISK_REVIEW, summarize the risks and let the user confirm in the card.
            """.trimIndent(),
            permissionCategory = "skill",
            isDefaultTool = false
        )
    }
}
