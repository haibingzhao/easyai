package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Parsed metadata, whole-directory checksum and declared version from one consistent read. */
data class SkillSnapshot(val info: SkillInfo, val checksum: String, val version: String)

/** Counters of one [SkillSyncService.syncFor] pass plus the publication delta it produced. */
data class SkillSyncOutcome(
    val owners: List<String> = emptyList(),
    val claimed: Int = 0,
    val pushed: Int = 0,

    /** Rows whose working copy was missing and came back from their package. */
    val restored: Int = 0,

    /** Rows whose working copy is current but whose package was absent from the resolved storage. */
    val backfilled: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,

    /** Shared-layer directories with no catalog row, deliberately left unclaimed. */
    val unclaimed: Int = 0,
    val delta: RegistryDelta? = null
)

sealed interface SkillAddResult {
    data class Added(val row: SkillCatalogEntry) : SkillAddResult

    /** The (owner, name) identity is taken; the caller must pick another name. */
    data class NameConflict(val message: String) : SkillAddResult

    /** Validation, packaging or catalog write failed; no row or directory was left behind. */
    data class Invalid(val message: String) : SkillAddResult
}

/** One file of an uploaded skill package, addressed relative to the skill directory. */
class SkillUploadFile(val relativePath: String, val content: ByteArray)

/** A browser-delivered skill package: individual files (folder picker) or one zip archive. */
sealed interface SkillUpload {
    class FromFiles(val entries: List<SkillUploadFile>) : SkillUpload
    class FromZip(val bytes: ByteArray) : SkillUpload
}

/**
 * DB-authoritative reconciliation between the catalog, object storage and each owner's local
 * skill root — the single write direction of the skill pipeline.
 *
 * For one owner: DB rows whose directory is missing are restored from the zip at `object_key`
 * (checksum-verified before the files become visible); directories whose digest drifted from the
 * row are treated as the fresher copy and pushed back (re-pack → upload → CAS `updateContent`);
 * directories no row claims are packed and claimed **in a personal root** — the shared `system`
 * layer exists only through its rows, so a hand-placed directory there is reported and left alone;
 * a row whose digest matches its directory but
 * whose object is absent from the storage the owner resolves to *now* is re-uploaded from that
 * directory, so content claimed before object storage existed still reaches the bucket.
 * Everything runs under a per-owner mutex so
 * login syncs, lazy first-access syncs, `addSkill` and `deleteSkill` for one owner serialize.
 *
 * Lock discipline: the `…Locked`-style primitives ([pushContent], [backfillPackage], [claimPackage],
 * [restore]) do NOT take the owner lock — callers that need serialization wrap them in [withOwnerLock]. The
 * indexer holds the same lock across a reconcile, so its content writes go through these
 * primitives directly and can never interleave with a sync pass.
 */
class SkillSyncService(
    private val catalog: AsyncSkillCatalogStore?,
    private val registry: SkillRegistry?,
    private val discovery: SkillDiscovery,
    private val packages: SkillPackageStore,
    private val config: SkillConfig
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val ownerLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withOwnerLock(owner: String, block: suspend () -> T): T =
        ownerLocks.computeIfAbsent(owner) { Mutex() }.withLock { block() }

    /** The normalized owner id a request maps to: blank or absent user ids are the shared layer. */
    fun ownerOf(userId: String?): String =
        userId?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_USER_ID

    /** Reconcile [userId]'s root and the shared `system` root; [SkillCatalogEntry.DEFAULT_USER_ID] only syncs itself. */
    suspend fun syncFor(userId: String?): SkillSyncOutcome {
        val owner = ownerOf(userId)
        val owners = if (owner == SkillCatalogEntry.DEFAULT_USER_ID) listOf(owner)
        else listOf(SkillCatalogEntry.DEFAULT_USER_ID, owner)
        val roots = owners.map { SkillPaths.ownerRoot(config, it) }.toSet()
        if (catalog == null) {
            // No catalog to reconcile against: keep the in-memory view fresh from disk only.
            return SkillSyncOutcome(owners = owners, delta = registry?.rescan(roots) ?: RegistryDelta())
        }
        var claimed = 0; var pushed = 0; var restored = 0; var backfilled = 0; var skipped = 0; var failed = 0
        var unclaimed = 0
        for (o in owners) {
            val partial = withOwnerLock(o) { syncOwner(o) }
            claimed += partial.claimed; pushed += partial.pushed; restored += partial.restored
            backfilled += partial.backfilled; skipped += partial.skipped; failed += partial.failed
            unclaimed += partial.unclaimed
        }
        // Publication last: restored, pushed and claimed directories become registry entries here.
        val delta = registry?.rescan(roots)
        return SkillSyncOutcome(
            owners = owners, claimed = claimed, pushed = pushed, restored = restored,
            backfilled = backfilled, skipped = skipped, failed = failed,
            unclaimed = unclaimed, delta = delta
        )
    }

    /**
     * Register one skill directory under [owner]/[name] through the full pipeline: validate →
     * copy into the owner root (rewriting the frontmatter name on mismatch) → pack → upload →
     * claim → publish. Must be called through the web layer, not concurrently with [syncFor]
     * on the same owner — both take the owner lock, so this never interleaves.
     */
    suspend fun addSkill(owner: String, name: String, sourceDir: Path): SkillAddResult =
        withOwnerLock(owner) {
            val store = catalog
                ?: return@withOwnerLock SkillAddResult.Invalid("Skill catalog is unavailable: enable easyai.r2dbc.enabled")
            val sanitized = SkillPaths.safeSegment(name)
            if (sanitized.isBlank() || sanitized != name.trim()) {
                return@withOwnerLock SkillAddResult.Invalid("Skill name must be a path-safe slug (letters, digits, dot, dash, underscore)")
            }
            val skillFile = sourceDir.resolve(SkillPaths.SKILL_FILE_NAME)
            if (!Files.isRegularFile(skillFile)) {
                return@withOwnerLock SkillAddResult.Invalid("Source directory has no SKILL.md: $sourceDir")
            }
            try {
                SkillLoader.parse(skillFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withOwnerLock SkillAddResult.Invalid("SKILL.md is invalid: ${e.message}")
            }
            if (store.findByName(owner, name) != null) {
                return@withOwnerLock SkillAddResult.NameConflict("Skill '$name' already exists for owner '$owner'")
            }
            val root = SkillPaths.ownerRoot(config, owner)
            val target = SkillPaths.installDir(root, name)
            // Picking a directory that already sits in the owner's root means claiming it in place;
            // copying onto itself would collide with the unclaimed-directory check below.
            val inPlace = SkillPaths.canonicalize(sourceDir) == SkillPaths.canonicalize(target)
            if (!inPlace && Files.exists(target)) {
                // An unclaimed directory with this name exists; claiming it would hijack whatever
                // produced it — the user must choose a different name.
                return@withOwnerLock SkillAddResult.NameConflict("A directory named '$name' already exists at ${SkillPaths.canonicalize(root)}")
            }
            try {
                if (!inPlace) {
                    // [snapshotOf] below aligns the frontmatter name with the target directory, so
                    // the copy needs no rewrite of its own.
                    withContext(Dispatchers.IO) { SkillPackages.copyTree(sourceDir, target) }
                }
                val snapshot = snapshotOf(target)
                    ?: throw IllegalArgumentException("SKILL.md disappeared during copy")
                when (val claim = claimPackage(owner, root, snapshot)) {
                    is PackageClaim.Owned -> SkillAddResult.Added(claim.row)
                    is PackageClaim.Conflict -> {
                        // A concurrent add claimed this identity first. Roll the copy back but keep
                        // the uploaded zip: deleting it would remove the winner's package too.
                        if (!inPlace) SkillPackages.deleteTree(target)
                        registry?.rescan(setOf(root))
                        SkillAddResult.NameConflict("Skill '$name' was claimed concurrently for owner '$owner'")
                    }
                    is PackageClaim.Failed -> {
                        if (!inPlace) SkillPackages.deleteTree(target)
                        SkillAddResult.Invalid(claim.error)
                    }
                }
            } catch (e: CancellationException) {
                if (!inPlace) SkillPackages.deleteTree(target)
                throw e
            } catch (e: Exception) {
                if (!inPlace) SkillPackages.deleteTree(target)
                SkillAddResult.Invalid("Adding skill '$name' failed: ${e.message}")
            }
        }

    /**
     * Register an uploaded package under [owner]/[name]: stage the bytes in a temporary directory —
     * outside every owner root, so a concurrent [syncFor] can never see a half-written skill — then
     * run the identical [addSkill] pipeline on the directory that carries `SKILL.md`.
     */
    suspend fun addUploaded(owner: String, name: String, upload: SkillUpload): SkillAddResult {
        val stage = try {
            stageUpload(upload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SkillAddResult.Invalid("Cannot stage the uploaded package: ${e.message}")
        }
        try {
            val sourceDir = stagedSkillDir(stage)
                ?: return SkillAddResult.Invalid(
                    "The upload has no SKILL.md, neither at its root nor in exactly one top-level folder"
                )
            return addSkill(owner, name, sourceDir)
        } finally {
            SkillPackages.deleteTree(stage)
        }
    }

    private suspend fun stageUpload(upload: SkillUpload): Path {
        val stage = withContext(Dispatchers.IO) { Files.createTempDirectory(STAGE_PREFIX) }
        try {
            writeUpload(stage, upload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SkillPackages.deleteTree(stage)
            throw e
        }
        return stage
    }

    private suspend fun writeUpload(stage: Path, upload: SkillUpload) {
        when (upload) {
            is SkillUpload.FromZip -> SkillPackages.unpack(upload.bytes, stage, config.packageMaxBytes)
            is SkillUpload.FromFiles -> {
                require(upload.entries.isNotEmpty()) { "Upload carries no files" }
                var written = 0L
                for (entry in upload.entries) {
                    val relative = entry.relativePath.replace('\\', '/')
                    require(relative.isNotBlank() && !relative.startsWith("/") && !relative.split('/').contains("..")) {
                        "Unsafe upload path: ${entry.relativePath}"
                    }
                    written += entry.content.size
                    require(written <= config.packageMaxBytes) {
                        "Upload expands beyond the ${config.packageMaxBytes}B package cap"
                    }
                    val out = stage.resolve(relative).normalize()
                    require(out.startsWith(stage) && out != stage) { "Unsafe upload path: ${entry.relativePath}" }
                    withContext(Dispatchers.IO) {
                        Files.createDirectories(out.parent)
                        Files.write(out, entry.content)
                    }
                }
            }
        }
    }

    /** The staged directory holding `SKILL.md`: the stage root itself, or its single skill folder. */
    private suspend fun stagedSkillDir(stage: Path): Path? = withContext(Dispatchers.IO) {
        if (Files.isRegularFile(stage.resolve(SkillPaths.SKILL_FILE_NAME))) return@withContext stage
        Files.list(stage).use { stream ->
            stream.filter { dir ->
                Files.isDirectory(dir) && Files.isRegularFile(dir.resolve(SkillPaths.SKILL_FILE_NAME))
            }.toList().singleOrNull()
        }
    }

    /**
     * Remove one row and every trace of it: catalog row first (fail-closed for loads), then the
     * registry entry, the package and the local directory. Returns the deleted row so the caller
     * can delist the retrieval document; null when the owner had no such skill.
     */
    suspend fun deleteSkill(owner: String, name: String): SkillCatalogEntry? = withOwnerLock(owner) {
        val store = catalog ?: return@withOwnerLock null
        val row = store.findByName(owner, name) ?: return@withOwnerLock null
        if (!store.delete(row.id)) return@withOwnerLock null
        registry?.remove(owner, name)
        packages.deleteQuietly(owner, row.objectKey)
        val path = SkillPaths.canonicalizeOrNull(row.installPath)?.let { Path.of(it) }
        val root = SkillPaths.ownerRoot(config, owner)
        if (path != null && SkillPaths.isWithin(root, path)) {
            SkillPackages.deleteTree(path)
        }
        row
    }

    suspend fun snapshotOf(entry: SkillCatalogEntry): SkillSnapshot? = snapshotOf(Path.of(entry.installPath))

    /** Parse + digest one skill directory; null when the directory or its SKILL.md is gone. */
    suspend fun snapshotOf(installDir: Path): SkillSnapshot? {
        val dir = installDir.toAbsolutePath().normalize()
        val (info, frontmatter) = withContext(Dispatchers.IO) {
            val skillFile = dir.resolve(SkillPaths.SKILL_FILE_NAME)
            if (!Files.isDirectory(dir) || !Files.isRegularFile(skillFile)) return@withContext null
            var parsed = SkillLoader.parseWithFrontmatter(skillFile)
            // The installed directory name is the skill's identity: it keys the catalog row, the
            // object key and the retrieval document. Frontmatter drift is repaired here so the
            // digest below, the registry entry and the row all carry the same name.
            val segment = dir.fileName?.toString()
            if (segment != null && SkillPaths.safeSegment(segment) == segment && parsed.first.name != segment) {
                logger.info(
                    "Skill '{}' declares a name other than its directory '{}'; rewriting the frontmatter",
                    parsed.first.name, segment
                )
                SkillLoader.rewriteName(skillFile, segment)
                parsed = SkillLoader.parseWithFrontmatter(skillFile)
            }
            parsed
        } ?: return null
        val checksum = SkillChecksums.dirDigest(dir) ?: return null
        val version = frontmatter["version"]?.toString()?.takeIf { it.isNotBlank() }
            ?: SkillCatalogEntry.DEFAULT_VERSION
        return SkillSnapshot(info, checksum, version)
    }

    fun publish(owner: String, snapshot: SkillSnapshot) {
        registry?.register(owner, snapshot.info)
    }

    /**
     * Push the local directory back into the catalog + package for [row]: pack → upload (same key
     * overwrites) → CAS `updateContent`. Returns false when the CAS lost (a concurrent edit or
     * toggle) — the caller re-reads the row and retries on the next pass. Owner lock held by caller.
     */
    internal suspend fun pushContent(
        owner: String, row: SkillCatalogEntry, snapshot: SkillSnapshot, enable: Boolean = false
    ): Boolean {
        val store = catalog ?: return false
        val key = row.objectKey.ifBlank {
            logger.warn("Skill row {} carries no object key; deriving '{}'", row.id, packages.keyFor(owner, row.name))
            packages.keyFor(owner, row.name)
        }
        val bytes = SkillPackages.pack(Path.of(SkillPaths.canonicalizeOrNull(row.installPath) ?: return false), config.packageMaxBytes)
        packages.storageFor(owner).put(key, bytes, ZIP_CONTENT_TYPE)
        return store.updateContent(row.id, row.revision, snapshot.checksum, snapshot.version, enable)
    }

    /**
     * Re-upload the package of a row whose working copy is current but whose object is missing from
     * the storage this owner resolves to **now** — the shape left behind when object storage is
     * configured after the skills were claimed into another backend. The bytes come from the
     * directory whose digest already equals `row.checksum`, so the row needs no write and the index
     * projection stays untouched. Returns false when the object is already there.
     *
     * [presentKeys] is the pass's one listing of the owner's package namespace; null means it was
     * unavailable and this row falls back to a HEAD. Caller holds the owner lock.
     */
    internal suspend fun backfillPackage(
        owner: String, installDir: Path, row: SkillCatalogEntry, presentKeys: Set<String>?
    ): Boolean {
        val key = row.objectKey.ifBlank { packages.keyFor(owner, row.name) }
        val present = if (presentKeys != null && key.startsWith(packages.packagePrefix(owner))) {
            key in presentKeys
        } else {
            // Not covered by the listing — it failed, or this key sits outside the namespace — so probe the key.
            packages.hasPackage(owner, key)
        }
        if (present) return false
        val bytes = SkillPackages.pack(installDir, config.packageMaxBytes)
        packages.storageFor(owner).put(key, bytes, ZIP_CONTENT_TYPE)
        logger.info("Skill package {} was missing; re-uploaded it for owner '{}'", key, owner)
        return true
    }

    /** Pack the snapshot's directory, upload it, then insert the row; conflict never overwrites. Caller holds the owner lock. */
    internal suspend fun claimPackage(owner: String, root: Path, snapshot: SkillSnapshot): PackageClaim {
        val store = catalog ?: return PackageClaim.Failed("Skill catalog is unavailable")
        val dir = snapshot.info.location.parent
            ?: return PackageClaim.Failed("Skill source is not an installed directory: ${snapshot.info.name}")
        // A row's name drives `install_path = {root}/{name}`, the object key and the retrieval
        // document key. [snapshotOf] already aligned the frontmatter with the directory segment, so
        // a segment that is not a path-safe slug can never become a row: no later pass could pair it
        // back to this directory or restore it.
        val name = snapshot.info.name
        val segment = dir.fileName?.toString().orEmpty()
        if (segment != name || SkillPaths.safeSegment(segment) != segment) {
            return PackageClaim.Failed("Skill directory name is not a path-safe slug: $segment")
        }
        val key = packages.keyFor(owner, name)
        val bytes = try {
            SkillPackages.pack(dir, config.packageMaxBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PackageClaim.Failed("Packaging '$name' failed: ${e.message}")
        }
        try {
            packages.storageFor(owner).put(key, bytes, ZIP_CONTENT_TYPE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PackageClaim.Failed("Uploading package for '$name' failed: ${e.message}")
        }
        val expectedId = UUID.randomUUID().toString()
        val row = try {
            store.claim(
                SkillCatalogEntry(
                    id = expectedId, name = name, checksum = snapshot.checksum, version = snapshot.version,
                    rootPath = SkillPaths.canonicalize(root), installPath = SkillPaths.canonicalize(dir),
                    objectKey = key, userId = owner
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            packages.deleteQuietly(owner, key)
            return PackageClaim.Failed("Claiming '$name' failed: ${e.message}")
        }
        if (row.id != expectedId) return PackageClaim.Conflict(row)
        publish(owner, snapshot)
        return PackageClaim.Owned(row)
    }

    private data class OwnerCounters(
        val claimed: Int, val pushed: Int, val restored: Int, val backfilled: Int,
        val skipped: Int, val failed: Int, val unclaimed: Int = 0
    )

    /** The owner's package keys as they exist right now; null when the namespace could not be listed. */
    private suspend fun listPackages(owner: String): Set<String>? = try {
        packages.presentPackageKeys(owner)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(
            "Cannot list the skill packages of '{}'; falling back to one HEAD per row: {}", owner, e.message
        )
        null
    }

    private suspend fun syncOwner(owner: String): OwnerCounters {
        val store = requireNotNull(catalog)
        val root = SkillPaths.ownerRoot(config, owner)
        withContext(Dispatchers.IO) { Files.createDirectories(root) }
        val rows = store.listByUser(owner)
        // root_path is authoritative: sanitized directory segments can differ from owner ids.
        val pinnings = HashMap<String, String>()
        pinnings[SkillPaths.canonicalize(root)] = owner
        rows.forEach { row ->
            SkillPaths.canonicalizeOrNull(row.rootPath)?.let { pinnings[it] = row.userId }
        }
        registry?.pinOwners(pinnings)
        // Keyed by directory segment, which is the catalog identity: a SKILL.md whose frontmatter
        // name drifted still pairs with its row here, and [snapshotOf] repairs the drift.
        val disk = withContext(Dispatchers.IO) { discovery.discoverOwnerRoot(root) }
            .associateBy { it.location.parent?.fileName?.toString() ?: it.name }
        // One listing covers every checksum-quiet row in this pass. Losing it is not a reason to
        // stop reconciling: the rows fall back to per-key HEADs, the cost this had before.
        val presentKeys = listPackages(owner)
        var claimed = 0; var pushed = 0; var restored = 0; var backfilled = 0; var skipped = 0; var failed = 0
        for (row in rows) {
            try {
                val skill = disk[row.name]
                if (skill == null) {
                    if (restore(owner, root, row)) restored++ else skipped++
                    continue
                }
                val dir = skill.location.parent ?: continue
                if (SkillPaths.canonicalizeOrNull(row.installPath) != SkillPaths.canonicalize(dir)) {
                    logger.warn(
                        "Skill '{}' of owner '{}' pins {} but the same name was found at {}; leaving both untouched",
                        row.name, owner, row.installPath, dir
                    )
                    skipped++
                    continue
                }
                val snapshot = snapshotOf(dir) ?: continue
                when {
                    snapshot.checksum != row.checksum -> {
                        if (pushContent(owner, row, snapshot)) pushed++ else skipped++
                    }
                    else -> if (backfillPackage(owner, dir, row, presentKeys)) backfilled++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                logger.warn("Skill sync could not process row '{}' of '{}': {}", row.name, owner, e.message)
            }
        }
        val orphaned = disk.filterKeys { name -> rows.none { it.name == name } }
        // The shared layer exists only through its catalog rows. Claiming an unclaimed directory
        // would let anyone who can write `{rootDir}/system` publish a skill every user can load,
        // and no user asked for it — report the leftovers and touch nothing.
        if (owner == SkillCatalogEntry.DEFAULT_USER_ID && orphaned.isNotEmpty()) {
            logger.warn(
                "Leaving {} directory-only skill(s) unclaimed in the shared root {}: {}",
                orphaned.size, root, orphaned.keys
            )
            return OwnerCounters(
                claimed = claimed, pushed = pushed, restored = restored, backfilled = backfilled,
                skipped = skipped, failed = failed, unclaimed = orphaned.size
            )
        }
        for ((name, skill) in orphaned) {
            try {
                val dir = skill.location.parent ?: continue
                val snapshot = snapshotOf(dir) ?: continue
                when (val claim = claimPackage(owner, root, snapshot)) {
                    is PackageClaim.Owned -> claimed++
                    is PackageClaim.Conflict -> {
                        logger.warn("Directory-only skill '{}' conflicts with row {} of '{}'", name, claim.existing.id, owner)
                        skipped++
                    }
                    is PackageClaim.Failed -> {
                        failed++
                        logger.warn("Cannot claim local skill '{}' of '{}': {}", name, owner, claim.error)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                logger.warn("Skill sync could not claim directory '{}' of '{}': {}", name, owner, e.message)
            }
        }
        return OwnerCounters(
            claimed = claimed, pushed = pushed, restored = restored, backfilled = backfilled,
            skipped = skipped, failed = failed
        )
    }

    /** Download and checksum-verify the row's package into its expected directory. Caller holds the owner lock. */
    private suspend fun restore(owner: String, root: Path, row: SkillCatalogEntry): Boolean {
        val expected = SkillPaths.installDir(root, row.name)
        if (SkillPaths.canonicalizeOrNull(row.installPath) != SkillPaths.canonicalize(expected)) {
            logger.warn("Skill '{}' of owner '{}' installs outside its owner root: {}", row.name, owner, row.installPath)
            return false
        }
        // Discovery only reports parsable skills, so an existing SKILL.md here means content the
        // sync could not read — local edits, not a leftover. Never overwrite those; the user fixes
        // or deletes the directory, and the next pass reconciles it.
        if (withContext(Dispatchers.IO) { Files.isRegularFile(expected.resolve(SkillPaths.SKILL_FILE_NAME)) }) {
            logger.warn(
                "Skill '{}' of owner '{}' has an unreadable local directory at {}; refusing to restore over it",
                row.name, owner, expected
            )
            return false
        }
        if (row.objectKey.isBlank()) {
            logger.warn("Skill '{}' of owner '{}' has no package key; nothing to restore", row.name, owner)
            return false
        }
        val content = try {
            packages.storageFor(owner).get(SkillPackageStore.requirePackageKey(row.objectKey))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot fetch package for '{}': {}", row.name, e.message)
            return false
        }
        if (content == null) {
            logger.warn("Skill package '{}' is gone; keeping row '{}' unsynced", row.objectKey, row.name)
            return false
        }
        val tmp = withContext(Dispatchers.IO) { Files.createTempDirectory(root, RESTORE_PREFIX) }
        return try {
            SkillPackages.unpack(content.bytes, tmp, config.packageMaxBytes)
            val digest = SkillChecksums.dirDigest(tmp)
            if (digest != row.checksum) {
                logger.warn(
                    "Package for '{}' of owner '{}' digests {}; catalog expects {} — refused",
                    row.name, owner, digest, row.checksum
                )
                false
            } else {
                withContext(Dispatchers.IO) {
                    // A half-written directory from a failed earlier pass is replaced wholesale.
                    SkillPackages.deleteTree(expected)
                    Files.move(tmp, expected)
                }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Restore of '{}' from {} failed: {}", row.name, row.objectKey, e.message)
            false
        } finally {
            SkillPackages.deleteTree(tmp)
        }
    }

    companion object {
        const val ZIP_CONTENT_TYPE = "application/zip"
        private const val RESTORE_PREFIX = ".restore-"
        private const val STAGE_PREFIX = "easyai-skill-upload-"
    }
}

/** Outcome of [SkillSyncService.claimPackage]: inserted, out-claimed by an existing row, or failed. */
internal sealed interface PackageClaim {
    data class Owned(val row: SkillCatalogEntry) : PackageClaim
    data class Conflict(val existing: SkillCatalogEntry) : PackageClaim
    data class Failed(val error: String) : PackageClaim
}
