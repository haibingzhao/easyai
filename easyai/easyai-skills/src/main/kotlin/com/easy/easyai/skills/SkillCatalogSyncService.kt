package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * Outcome of comparing one catalog row against the file on disk.
 *
 * Only [Content] justifies a backend write; that distinction is what turns startup from
 * "N upsert requests every boot" into "N stat calls, plus a write for rows that really moved".
 */
sealed interface SkillDrift {
    /** Disk content still matches the recorded checksum — nothing to do. */
    data object None : SkillDrift

    /** SKILL.md bytes changed: re-index and persist the new fingerprint. */
    data class Content(val newChecksum: String, val newVersion: String) : SkillDrift

    /** Row exists but the file does not (directory hand-deleted or moved machine). */
    data object Missing : SkillDrift
}

/**
 * Two-way alignment between the skill directory tree and the `skill` catalog table.
 *
 * [backfillAll] is the only claim path for skills that predate the catalog: without a row they
 * would never be indexed under any owner, so `skill_search` could not find them at all.
 * [driftOf] is the reconciliation primitive the indexer builds on.
 *
 * [config] lets the claim pass derive each skill's granularity ([SkillScopeResolver]) so the row
 * lands under the right (owner, name, project_hash) coordinates — two projects' same-named skills
 * claim two independent rows.
 */
class SkillCatalogSyncService(
    private val catalog: AsyncSkillCatalogStore?,
    private val config: SkillConfig = SkillConfig(),
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Process-local `catalog id -> mtime already verified as unchanged`.
     *
     * Purely an optimization: it lets a steady-state pass cost one `stat` instead of a full
     * file read + hash. Correctness across restarts comes from the persisted checksum, never
     * from this map, so a lost entry only costs one extra hash.
     *
     * Bounded via [invalidate] calls from `SkillIndexer.removeOne` / `delist`; without that
     * eviction, rows deleted over the process lifetime would leave stale ids here forever.
     */
    private val verifiedMtimes = ConcurrentHashMap<String, Long>()

    /**
     * Insert a catalog row for every discovered skill that has none at its own install directory,
     * owned by [defaultUserId] (filesystem-discovered skills carry no user information).
     *
     * Idempotency is per (name, granularity): an existing same-named row for a *different* project
     * must not suppress this skill's claim — only a row pointing at the very same directory does.
     *
     * One [AsyncSkillCatalogStore.listByUser] round trip up front replaces what used to be an
     * N-query loop (`listByName` per skill). With 100 skills discovered on a fresh workspace that
     * is 100 queries collapsed into 1, and the local `(name, install_path)` index is what makes
     * the "same directory already claimed" check O(1).
     *
     * @return number of rows created; idempotent, a second pass returns 0
     */
    suspend fun backfillAll(
        discovered: List<SkillInfo>,
        defaultUserId: String = SkillCatalogEntry.DEFAULT_USER_ID
    ): Int {
        val store = catalog ?: return 0
        if (discovered.isEmpty()) return 0

        val existingRows = try {
            store.listByUser(defaultUserId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Skill catalog backfill could not preload existing rows: {}", e.message)
            emptyList()
        }
        // (name, canonical install_path) -> already claimed. Two same-named skills under different
        // project roots land in different buckets, matching the UNIQUE index semantics.
        val claimed = HashMap<Pair<String, String>, Unit>(existingRows.size * 2)
        for (row in existingRows) {
            val canonical = SkillPaths.canonicalizeOrNull(row.installPath) ?: continue
            claimed[row.name to canonical] = Unit
        }

        var created = 0
        for (skill in discovered) {
            try {
                val installDir = skill.location.parent ?: continue
                val canonicalDir = SkillPaths.canonicalize(installDir)
                if (claimed.containsKey(skill.name to canonicalDir)) continue
                val skillFile = installDir.resolve(SKILL_FILE_NAME).toFile()
                val checksum = sha256OfFile(skillFile)
                if (checksum == null) {
                    logger.warn("Skill '{}' has no readable SKILL.md at {}; not claimed", skill.name, installDir)
                    continue
                }
                val (_, projectPath) = SkillScopeResolver.resolve(skill, config)
                store.upsert(
                    SkillCatalogEntry(
                        name = skill.name,
                        source = SkillCatalogEntry.SOURCE_LOCAL,
                        version = declaredVersion(skillFile),
                        checksum = checksum,
                        enabled = true,
                        installPath = canonicalDir,
                        origin = null,
                        userId = defaultUserId,
                        projectHash = SkillScopeResolver.projectHashOf(projectPath)
                    )
                )
                claimed[skill.name to canonicalDir] = Unit
                created++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One unreadable skill must not abort the whole claim pass.
                logger.warn("Failed to claim skill '{}' into the catalog: {}", skill.name, e.message)
            }
        }
        logger.info("Skill catalog backfill claimed {} of {} discovered skills", created, discovered.size)
        return created
    }

    /**
     * Compare one catalog row with its SKILL.md on disk.
     *
     * A missing file and a file whose mtime is 0 are told apart with an explicit existence
     * check: `lastModified()` returns 0 for absent files, so trusting that value alone would
     * make `Missing` and "unchanged" indistinguishable.
     */
    suspend fun driftOf(entry: SkillCatalogEntry): SkillDrift = withContext(Dispatchers.IO) {
        val file = skillFileOf(entry)
        if (!file.isFile) {
            verifiedMtimes.remove(entry.id)
            return@withContext SkillDrift.Missing
        }
        val mtime = file.lastModified()
        if (mtime > 0 && verifiedMtimes[entry.id] == mtime) {
            return@withContext SkillDrift.None
        }
        val checksum = sha256OfFile(file)
        if (checksum == null) {
            // Unreadable content is treated as unchanged rather than missing: deleting the
            // index over a transient I/O error would be far worse than a stale hit.
            logger.warn("Skill '{}' could not be read ({}); leaving its index as is", entry.name, file)
            return@withContext SkillDrift.None
        }
        if (checksum == entry.checksum) {
            if (mtime > 0) verifiedMtimes[entry.id] = mtime
            return@withContext SkillDrift.None
        }
        SkillDrift.Content(checksum, declaredVersion(file))
    }

    /** Version declared in a row's SKILL.md frontmatter, or [SkillCatalogEntry.DEFAULT_VERSION]. */
    suspend fun declaredVersionOf(entry: SkillCatalogEntry): String = withContext(Dispatchers.IO) {
        declaredVersion(skillFileOf(entry))
    }

    /** Drop the memoised mtime for a row, forcing a fresh hash on the next comparison. */
    fun invalidate(id: String) {
        verifiedMtimes.remove(id)
    }

    /** SHA-256 hex of a row's SKILL.md, or null when it cannot be read. */
    suspend fun checksumOf(entry: SkillCatalogEntry): String? = withContext(Dispatchers.IO) {
        sha256OfFile(skillFileOf(entry))
    }

    /** SHA-256 hex of one SKILL.md file, or null when it cannot be read. */
    suspend fun checksumOf(installDir: Path): String? = withContext(Dispatchers.IO) {
        sha256OfFile(installDir.resolve(SKILL_FILE_NAME).toFile())
    }

    private fun skillFileOf(entry: SkillCatalogEntry): File =
        File(Path.of(entry.installPath).resolve(SKILL_FILE_NAME).toString())

    /**
     * Read the `version` declared in a SKILL.md frontmatter, falling back to the default.
     *
     * Streaming on purpose: frontmatter is capped at 500 lines by the skill contract, so reading
     * the whole file just to look at the first block would waste I/O on skills that embed large
     * reference-style examples in the body. [FRONTMATTER_MAX_LINES] leaves comfortable
     * headroom over the 500-line rule.
     */
    private fun declaredVersion(file: File): String = try {
        if (!file.isFile) return SkillCatalogEntry.DEFAULT_VERSION
        BufferedReader(FileReader(file, Charsets.UTF_8)).use { reader ->
            val lines = reader.lineSequence().take(FRONTMATTER_MAX_LINES).toList()
            val (frontmatter, _) = SkillLoader.extractFrontmatter(lines.joinToString("\n"))
            when (val raw = frontmatter["version"]) {
                is String -> raw.takeIf { it.isNotBlank() }
                is Number -> raw.toString()
                else -> null
            } ?: SkillCatalogEntry.DEFAULT_VERSION
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.debug("Failed to read version from {}: {}", file, e.message)
        SkillCatalogEntry.DEFAULT_VERSION
    }

    /**
     * SHA-256 of one SKILL.md, streamed through a [DigestInputStream] rather than a full
     * `readBytes()`: skills can carry multi-MB bodies and this runs once per drift check.
     */
    private fun sha256OfFile(file: File): String? = try {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            DigestInputStream(input, digest).use { dis ->
                val buffer = ByteArray(HASH_BUFFER_SIZE)
                while (dis.read(buffer) != -1) {
                    // Drain: DigestInputStream updates the digest as bytes flow through.
                }
            }
        }
        HEX.formatHex(digest.digest())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Failed to hash {}: {}", file, e.message)
        null
    }

    companion object {
        const val SKILL_FILE_NAME = "SKILL.md"

        /** Frontmatter is capped at 500 lines by contract; 1000 gives room for stray whitespace. */
        private const val FRONTMATTER_MAX_LINES = 1000

        /** 8 KiB is the sweet spot for `DigestInputStream` on typical SSD-backed workspaces. */
        private const val HASH_BUFFER_SIZE = 8 * 1024

        private val HEX: HexFormat = HexFormat.of()
    }
}
