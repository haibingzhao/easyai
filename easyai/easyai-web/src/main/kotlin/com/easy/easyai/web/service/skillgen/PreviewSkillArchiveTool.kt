package com.easy.easyai.web.service.skillgen

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import com.easy.easyai.skills.SkillAccessResolver
import com.easy.easyai.skills.SkillArchiveCandidate
import com.easy.easyai.skills.SkillArchiveInspector
import com.easy.easyai.skills.SkillConfig
import com.easy.easyai.skills.SkillScanSeverity
import com.easy.easyai.tools.web.WebUrlSafety
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * Downloads a skill archive and returns one review card per skill it contains. It never installs:
 * the user clicks Install (or Install all) on the card, which posts `/api/skills/agent/install`
 * with the pinned SHA-256.
 *
 * No file content enters the tool output — only names, paths, sizes and scan verdicts. A vendor
 * bundle with several skills therefore costs the model a few hundred tokens, not the archive.
 */
internal class PreviewSkillArchiveTool(
    private val skillConfig: SkillConfig?,
    private val catalog: AsyncSkillCatalogStore?,
    private val resolver: SkillAccessResolver?,
    private val writeOwner: String,
    private val userId: String,
    private val owners: Collection<String>,
    private val fetcher: SkillArchiveFetcher = SkillArchiveFetcher(),
    private val inspector: SkillArchiveInspector = SkillArchiveInspector()
) : BaseToolDefinition(METADATA) {

    data class Parameters(
        @field:JsonPropertyDescription(
            "Direct http(s) URL of a skill archive (.zip), exactly as given by the user or linked from an install document."
        )
        val url: String,
        @field:JsonPropertyDescription(
            "Optional map of entryPath (as reported by a previous preview; empty string means the archive root) " +
                "to a new slug name. Use it to resolve a name conflict without editing the archive."
        )
        val renames: Map<String, String>? = null
    )

    override fun parameterType(): Class<*> = Parameters::class.java

    private val preflight = SkillProposalPreflight(skillConfig, catalog, resolver, writeOwner, owners)

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val url = (args["url"] as? String)?.trim().orEmpty()
        if (!WebUrlSafety.isHttpUrl(url)) {
            return errorResult(
                "${SkillArchiveCodes.FETCH_FAILED}: provide the direct http(s) URL of a .zip skill archive.\n$NO_SUBSTITUTE"
            )
        }
        val renames = renamesOf(args["renames"])
            ?: return errorResult("INVALID_RENAMES: renames must be an object mapping entryPath to a new slug name.")
        renames.values.firstOrNull { !isValidSkillName(it) }?.let { invalid ->
            return errorResult(
                "INVALID_NAME: '${invalid.take(80)}' is not a path-safe slug " +
                    "(letters, digits, dot, dash, underscore, at most $SKILL_NAME_MAX_LENGTH_CHARS characters)."
            )
        }

        onUpdate(ToolUpdate.Progress("Downloading $url"))
        val maxBytes = archiveCap()
        val fetched = try {
            fetcher.fetch(url, maxBytes)
        } catch (e: SkillArchiveException) {
            return errorResult("${e.code}: ${e.message}\n$NO_SUBSTITUTE")
        }

        onUpdate(ToolUpdate.Progress("Inspecting ${fetched.bytes.size} archive bytes"))
        val candidates = try {
            inspector.inspect(fetched.bytes, maxBytes, renames)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return errorResult(
                "${SkillArchiveCodes.NOT_A_ZIP}: could not unpack ${fetched.finalUrl}: ${e.message}\n$NO_SUBSTITUTE"
            )
        }
        if (candidates.isEmpty()) {
            return errorResult(
                "${SkillArchiveCodes.NO_SKILL}: ${fetched.finalUrl} contains no directory with a SKILL.md, " +
                    "so it carries no skill to install.\n$NO_SUBSTITUTE"
            )
        }

        val target = targetLayer(writeOwner, userId)
        val items = candidates.map { candidate -> itemOf(candidate) }
        val unmatched = renames.keys - candidates.map { it.entryPath }.toSet()

        val proposal = buildMap<String, Any> {
            put("sourceUrl", url)
            put("finalUrl", fetched.finalUrl)
            put("sha256", fetched.sha256)
            put("archiveBytes", fetched.bytes.size)
            put("target", target)
            put("skills", items.map { it.payload })
        }
        return ToolResult(
            content = listOf(TextContent(contentOf(fetched, target, items, renames, unmatched))),
            details = mapOf("skillArchiveProposal" to proposal),
            terminate = items.none { it.scan.requiresAcknowledgement }
        )
    }

    private suspend fun itemOf(candidate: SkillArchiveCandidate): CandidateItem {
        val nameValid = isValidSkillName(candidate.name)
        val checks = if (nameValid) preflight.check(candidate.name) else SkillPreflight(emptyList(), emptyList())
        val invalidReason = when {
            !nameValid -> "The skill name '${candidate.name.take(80)}' is not a path-safe slug; " +
                "rename it via preview_skill_archive and preview again."
            candidate.invalidReason != null -> candidate.invalidReason
            candidate.scan.blocking -> "Blocked by the server scan: " + candidate.scan.findings
                .filter { it.blocking }
                .take(5)
                .joinToString("; ") { it.id }
            else -> null
        }
        val warnings = buildList {
            candidate.scan.findings
                .filter { it.severity == SkillScanSeverity.HIGH && !it.blocking }
                .forEach { add(it.message) }
            addAll(checks.warnings)
        }.distinct().take(MAX_WARNINGS)
        val payload = buildMap<String, Any> {
            put("entryPath", candidate.entryPath)
            put("name", candidate.name)
            put("description", candidate.description ?: "")
            put("version", candidate.version)
            put("tags", candidate.tags.take(MAX_TAGS).map { it.take(MAX_TAG_CHARS) })
            put("files", candidate.files.take(MAX_FILES_IN_PAYLOAD).map { file ->
                mapOf("path" to file.relativePath, "size" to file.content.size)
            })
            put("scan", SkillScanPayload.of(candidate.scan))
            put("warnings", warnings)
            checks.conflicts.joinToString(" ").takeIf { it.isNotBlank() }?.let { put("conflict", it) }
            invalidReason?.let { put("invalidReason", it) }
        }
        return CandidateItem(candidate, payload, checks.conflicts.isNotEmpty() || invalidReason != null)
    }

    private fun contentOf(
        fetched: FetchedArchive,
        target: String,
        items: List<CandidateItem>,
        renames: Map<String, String>,
        unmatched: Set<String>
    ): String = buildString {
        val installable = items.count { it.installable }
        appendLine(
            "ARCHIVE_PREVIEW_READY: ${items.size} skill(s) found in ${fetched.finalUrl}; " +
                "$installable installable. Nothing is installed."
        )
        appendLine("Target bucket: $target. Archive: ${fetched.bytes.size} bytes, sha256 ${fetched.sha256.take(12)}…")
        items.forEach { item ->
            val location = item.candidate.entryPath.ifEmpty { "(archive root)" }
            append("- $location → '${item.candidate.name}' (${item.candidate.files.size} files)")
            append(
                when {
                    item.conflict -> " — CONFLICT, not installable"
                    item.candidate.scan.requiresAcknowledgement -> " — high-risk, the user must tick the box"
                    item.installable -> " — installable"
                    else -> " — NOT installable"
                }
            )
            appendLine()
            item.entryConflict?.let { appendLine("  conflict: $it") }
        }
        if (renames.isNotEmpty()) {
            appendLine("Renames applied: " + renames.entries.joinToString(", ") { "${it.key.ifEmpty { "(root)" }} → ${it.value}" })
        }
        if (unmatched.isNotEmpty()) {
            appendLine("Ignored renames (no such entryPath in this archive): ${unmatched.joinToString(", ")}")
        }
        appendLine("The card(s) are shown to the user; the user clicks Install or Install all. Do not claim any skill is installed.")
        append(NO_SUBSTITUTE)
    }

    private fun renamesOf(raw: Any?): Map<String, String>? {
        if (raw == null) return emptyMap()
        val map = raw as? Map<*, *> ?: return null
        val result = LinkedHashMap<String, String>()
        for ((rawKey, rawValue) in map) {
            val key = rawKey as? String ?: return null
            val value = (rawValue as? String)?.trim() ?: return null
            result[key] = value
        }
        return result
    }

    private fun archiveCap(): Long =
        (skillConfig?.packageMaxBytes ?: SKILL_ARCHIVE_MAX_BYTES).coerceAtMost(SKILL_ARCHIVE_MAX_BYTES)

    private data class CandidateItem(
        val candidate: SkillArchiveCandidate,
        val payload: Map<String, Any>,
        val conflict: Boolean
    ) {
        val installable: Boolean get() = !conflict
        val scan get() = candidate.scan
        val entryConflict: String? get() = payload["conflict"] as? String
    }

    private companion object {
        private const val MAX_TAGS = 20
        private const val MAX_TAG_CHARS = 64
        private const val MAX_WARNINGS = 20
        private const val MAX_FILES_IN_PAYLOAD = 200
        private const val SKILL_NAME_MAX_LENGTH_CHARS = 64

        private val NO_SUBSTITUTE =
            "If the archive cannot be previewed, report this exact URL and error to the user and stop. " +
                "Never replace the requested package with a guide, documentation skill or any other deliverable."

        private val METADATA = ToolMetadata(
            name = "preview_skill_archive",
            description = """
                Download a .zip skill archive, scan every skill it contains and return one preview
                card per skill. Never installs and never writes files. Use this for any zip/archive
                URL, including archives linked from an install document. On ARCHIVE_* errors, report
                the exact URL and error; do not author a substitute skill.
            """.trimIndent(),
            permissionCategory = "skill",
            isDefaultTool = false
        )
    }
}
