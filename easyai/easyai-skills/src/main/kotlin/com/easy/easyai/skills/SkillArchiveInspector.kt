package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillCatalogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * One skill discovered inside a downloaded archive.
 *
 * @param entryPath skill root inside the archive: `""` for the archive root, otherwise a
 *   forward-slash relative path such as `search-skills/news-search`
 * @param files every file of the skill, addressed relative to [entryPath]; contents are kept so the
 *   install path can reuse the normal upload pipeline, but clients only ever receive paths and sizes
 * @param invalidReason non-null when this entry must not be installed (unparsable SKILL.md, caps
 *   exceeded, duplicate name in the same archive); the other entries stay installable
 */
data class SkillArchiveCandidate(
    val entryPath: String,
    val name: String,
    val description: String?,
    val version: String,
    val tags: List<String>,
    val files: List<SkillUploadFile>,
    val scan: SkillScanReport,
    val invalidReason: String? = null
)

/**
 * Unpacks a downloaded skill archive and inspects every skill it carries, without installing
 * anything.
 *
 * An archive may hold one skill at its root (the normal single-package layout) or a directory tree
 * of many skills, as vendor bundles do. Every directory that directly contains `SKILL.md` is a
 * candidate; a candidate nested inside another one is not separate discovery (its files stay part
 * of the outer skill). Renames are applied to the temp copy **before** scanning because the scanner
 * treats a frontmatter/directory name mismatch as blocking.
 *
 * The temp tree never escapes this module: it is deleted in [inspect]'s `finally`, including on
 * cancellation.
 */
class SkillArchiveInspector {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Unpack [bytes] (already size-checked by the caller), discover its skills and scan each one.
     * Throws when the bytes are not a valid zip or violate the zip-safety rules; returns an empty
     * list when no `SKILL.md` exists anywhere.
     *
     * @param renames entryPath → new skill name (only entries already in the archive); applied
     *   before the scan so a renamed skill does not preview as `spec.name-mismatch`
     */
    suspend fun inspect(
        bytes: ByteArray,
        maxBytes: Long,
        renames: Map<String, String> = emptyMap()
    ): List<SkillArchiveCandidate> {
        val stage = withContext(Dispatchers.IO) { Files.createTempDirectory(TEMP_PREFIX) }
        try {
            SkillPackages.unpack(bytes, stage, maxBytes)
            val roots = withContext(Dispatchers.IO) { discoverSkillRoots(stage) }
            if (roots.isEmpty()) return emptyList()
            val discovered = withContext(Dispatchers.IO) {
                roots.map { load(stage, it, renames) }.sortedBy { it.entryPath }
            }
            return withContext(Dispatchers.Default) { toCandidates(discovered, maxBytes) }
        } finally {
            SkillPackages.deleteTree(stage)
        }
    }

    /** SHA-256 of the exact archive bytes; clients pin it so install re-downloads cannot drift. */
    fun sha256Hex(bytes: ByteArray): String = SkillChecksums.sha256Hex(bytes)

    // ── Discovery ──────────────────────────────────────────────────────────────

    /** Skill roots in [stage], shallowest first so a nested `SKILL.md` is never a second skill. */
    private fun discoverSkillRoots(stage: Path): List<Path> {
        val roots = mutableListOf<Path>()
        Files.walkFileTree(stage, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val segment = dir.fileName?.toString()
                if (dir != stage && segment != null && segment in JUNK_DIRS) return FileVisitResult.SKIP_SUBTREE
                if (Files.isRegularFile(dir.resolve(SkillPaths.SKILL_FILE_NAME))) {
                    roots.add(dir)
                    // Everything below a skill root belongs to that skill, not to a second one.
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }
        })
        return roots
    }

    private fun load(stage: Path, dir: Path, renames: Map<String, String>): Discovered {
        val entryPath = stage.relativize(dir).toString().replace('\\', '/')
        val skillFile = dir.resolve(SkillPaths.SKILL_FILE_NAME)
        val reasons = mutableListOf<String>()
        val fallbackName = entryPath.substringAfterLast('/').ifEmpty { UNNAMED_SKILL }
        var name = fallbackName
        var description: String? = null
        var version = SkillCatalogEntry.DEFAULT_VERSION
        var tags: List<String> = emptyList()
        var parsed = false
        try {
            val metadata = readMetadata(skillFile)
            name = metadata.name
            description = metadata.description
            version = metadata.version
            tags = metadata.tags
            parsed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reasons += "SKILL.md could not be parsed: ${e.message}"
        }
        if (parsed) {
            // A rename is a frontmatter rewrite of the temp copy: the scanner compares the declared
            // name against the requested one and would otherwise block every renamed entry.
            val renamed = renames[entryPath]?.trim()
            if (!renamed.isNullOrEmpty() && renamed != name) {
                try {
                    SkillLoader.rewriteName(skillFile, renamed)
                    val metadata = readMetadata(skillFile)
                    name = metadata.name
                    description = metadata.description
                    version = metadata.version
                    tags = metadata.tags
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reasons += "Could not rename the skill to '$renamed': ${e.message}"
                }
            }
        }
        val files = listFiles(dir)
        return Discovered(entryPath, name, description, version, tags, files, reasons)
    }

    private fun readMetadata(skillFile: Path): Metadata {
        val (info, frontmatter) = SkillLoader.parseWithFrontmatter(skillFile)
        val version = frontmatter["version"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: SkillCatalogEntry.DEFAULT_VERSION
        return Metadata(info.name, info.description, version, info.tags.toList())
    }

    private fun listFiles(dir: Path): List<SkillUploadFile> {
        val files = mutableListOf<SkillUploadFile>()
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                val segment = d.fileName?.toString()
                return if (d != dir && segment != null && segment in JUNK_DIRS) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val fileName = file.fileName?.toString()
                if (attrs.isRegularFile && (fileName == null || fileName !in JUNK_FILES)) {
                    val relative = dir.relativize(file).toString().replace('\\', '/')
                    files += SkillUploadFile(relative, Files.readAllBytes(file))
                }
                return FileVisitResult.CONTINUE
            }
        })
        return files.sortedBy { it.relativePath }
    }

    // ── Verdicts ───────────────────────────────────────────────────────────────

    private fun toCandidates(discovered: List<Discovered>, maxBytes: Long): List<SkillArchiveCandidate> {
        val seenNames = HashSet<String>()
        return discovered.mapIndexed { index, item ->
            val reasons = item.reasons.toMutableList()
            if (!seenNames.add(item.name)) {
                reasons += "Another skill in this archive is already named '${item.name}'; " +
                    "rename one of them and preview again."
            }
            when {
                index >= MAX_SKILLS -> {
                    reasons += "The archive contains more than $MAX_SKILLS skills; this one was not inspected."
                    candidate(item, rejectedReport(
                        "archive.skill-limit",
                        "Only the first $MAX_SKILLS skills of an archive are inspected",
                        "Split the archive, or install the first $MAX_SKILLS skills and preview the rest separately."
                    ), reasons)
                }
                item.files.size > MAX_FILES_PER_SKILL -> {
                    reasons += "The skill carries ${item.files.size} files; the limit is $MAX_FILES_PER_SKILL."
                    candidate(item, rejectedReport(
                        "archive.file-limit",
                        "The skill has ${item.files.size} files, over the $MAX_FILES_PER_SKILL-file limit",
                        "Remove generated or vendored files, or install this skill on its own."
                    ), reasons)
                }
                else -> {
                    val report = try {
                        SkillPackageScanner.scan(item.name, item.files, maxBytes, BinaryAssetPolicy.WARN)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Archive skill '{}' could not be scanned: {}", item.entryPath, e.message)
                        rejectedReport(
                            "archive.scan-failed",
                            "The skill could not be scanned: ${e.message}",
                            "Report this archive; the skill was not installed."
                        )
                    }
                    candidate(item, report, reasons)
                }
            }
        }
    }

    private fun candidate(
        item: Discovered,
        report: SkillScanReport,
        reasons: List<String>
    ): SkillArchiveCandidate = SkillArchiveCandidate(
        entryPath = item.entryPath,
        name = item.name,
        description = item.description,
        version = item.version,
        tags = item.tags,
        files = item.files,
        scan = report,
        invalidReason = reasons.takeIf { it.isNotEmpty() }?.joinToString(" ")
    )

    private fun rejectedReport(id: String, message: String, suggestion: String): SkillScanReport {
        val finding = SkillScanFinding(
            id = id,
            category = SkillScanCategory.SPEC,
            severity = SkillScanSeverity.HIGH,
            message = message,
            suggestion = suggestion,
            blocking = true
        )
        return SkillScanReport(
            findings = listOf(finding),
            blocking = true,
            requiresAcknowledgement = false,
            counts = SkillScanSeverity.entries.associate { it.wire to if (it == SkillScanSeverity.HIGH) 1 else 0 },
            categoryCounts = SkillScanCategory.entries.associate { category ->
                category.wire to SkillScanSeverity.entries.associate { severity ->
                    severity.wire to if (category == SkillScanCategory.SPEC && severity == SkillScanSeverity.HIGH) 1 else 0
                }
            }
        )
    }

    private data class Metadata(
        val name: String,
        val description: String?,
        val version: String,
        val tags: List<String>
    )

    private data class Discovered(
        val entryPath: String,
        val name: String,
        val description: String?,
        val version: String,
        val tags: List<String>,
        val files: List<SkillUploadFile>,
        val reasons: List<String>
    )

    private companion object {
        private const val TEMP_PREFIX = "easyai-skill-archive-"
        private const val UNNAMED_SKILL = "unnamed-skill"
        private const val MAX_SKILLS = 20
        private const val MAX_FILES_PER_SKILL = 200
        private val JUNK_DIRS = setOf("__MACOSX", ".git")
        private val JUNK_FILES = setOf(".DS_Store")
    }
}
