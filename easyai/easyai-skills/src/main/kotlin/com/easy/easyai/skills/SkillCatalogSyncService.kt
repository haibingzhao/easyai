package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

sealed interface SkillDrift {
    data object None : SkillDrift
    data class Content(val newChecksum: String, val newVersion: String) : SkillDrift
    data object Missing : SkillDrift
}

/** Parsed metadata, body and checksum all originate from one read of SKILL.md. */
data class SkillSnapshot(val info: SkillInfo, val checksum: String, val version: String)

data class SkillClaimSummary(val claimed: Int = 0, val failed: Int = 0, val unclaimed: Int = 0)

class SkillCatalogSyncService(
    private val catalog: AsyncSkillCatalogStore?,
    private val config: SkillConfig = SkillConfig(),
    registry: SkillRegistry? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val claimMutex = Mutex()
    // Bounded local stripes serialize all remote operations for an installation, including toggles.
    private val installationLocks = Array(64) { Mutex() }
    @Volatile private var publicationRegistry = registry

    internal fun bindRegistry(registry: SkillRegistry) { publicationRegistry = registry }

    internal suspend fun <T> withInstallation(installPath: String, block: suspend () -> T): T {
        val key = SkillPaths.canonicalize(Path.of(installPath))
        return installationLocks[(key.hashCode() and Int.MAX_VALUE) % installationLocks.size].withLock { block() }
    }

    internal fun publish(snapshot: SkillSnapshot) {
        checkNotNull(publicationRegistry) { "SkillCatalogSyncService requires a SkillRegistry for publication" }
            .register(snapshot.info)
    }

    /** Catalog reads must succeed before any claim; every owner reserves its installation path. */
    suspend fun claimUnclaimed(
        discovered: List<SkillInfo>, requestedUserId: String?, projectPath: Path? = null
    ): SkillClaimSummary = claimMutex.withLock {
        val store = catalog ?: return@withLock SkillClaimSummary(unclaimed = discovered.size)
        val rows = store.listAll()
        val claimedPaths = rows.mapNotNull { SkillPaths.canonicalizeOrNull(it.installPath) }.toMutableSet()
        val identities = rows.associateBy { Triple(it.userId, it.name, it.projectHash) }.toMutableMap()
        val owner = requestedUserId?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_USER_ID
        var created = 0
        var failed = 0
        var unclaimed = 0
        for (skill in discovered) {
            try {
                val dir = skill.location.parent ?: continue
                val canonical = SkillPaths.canonicalize(dir)
                if (canonical in claimedPaths) continue
                val identity = SkillScopeResolver.classify(skill, config)
                if (identity == null) {
                    unclaimed++
                    continue
                }
                val (scope, root) = identity
                val shared = isExplicitGlobal(dir)
                if ((scope == SkillScope.GLOBAL && !shared) ||
                    (owner == SkillCatalogEntry.DEFAULT_USER_ID && !shared) ||
                    (scope == SkillScope.PROJECT && (projectPath == null ||
                        SkillPaths.canonicalize(projectPath) != root?.let { SkillPaths.canonicalize(it) }))) {
                    unclaimed++
                    continue
                }
                val snapshot = snapshotOf(dir) ?: continue
                require(snapshot.info.name == skill.name) { "Skill name changed during discovery" }
                val hash = SkillScopeResolver.projectHashOf(root)
                val key = Triple(owner, skill.name, hash)
                check(key !in identities) { "Skill identity is already claimed at another path" }
                publish(snapshot)
                val claimId = UUID.randomUUID().toString()
                val row = store.claim(SkillCatalogEntry(
                    id = claimId, name = skill.name, checksum = snapshot.checksum, version = snapshot.version,
                    installPath = canonical, userId = owner, projectHash = hash,
                    indexProjectPath = root?.let { SkillPaths.canonicalize(it) }
                ))
                check(SkillPaths.canonicalizeOrNull(row.installPath) == canonical) { "Concurrent skill identity conflict" }
                claimedPaths.add(canonical)
                identities[key] = row
                if (row.id == claimId) created++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                logger.warn("Skill claim failed for '{}': {}", skill.name, e.message)
            }
        }
        SkillClaimSummary(created, failed, unclaimed)
    }

    /** Reuse the same explicit shared-root classification as the access layer. */
    internal fun isExplicitGlobal(installPath: Path): Boolean =
        SkillScopeResolver.classify(installPath, config)?.first == SkillScope.GLOBAL

    suspend fun snapshotOf(entry: SkillCatalogEntry): SkillSnapshot? = snapshotOf(Path.of(entry.installPath))

    suspend fun snapshotOf(installDir: Path): SkillSnapshot? = withContext(Dispatchers.IO) {
        val path = installDir.resolve(SKILL_FILE_NAME).toAbsolutePath().normalize()
        val bytes = try {
            Files.readAllBytes(path)
        } catch (e: NoSuchFileException) {
            // Do not turn an inaccessible mount/parent into deletion.
            if (Files.notExists(path)) return@withContext null
            throw e
        } catch (e: FileSystemException) {
            // ENOTDIR: the install directory was replaced by a regular file. A stat failure on the
            // directory itself stays an error so an unreadable mount can never look like a deletion.
            val attrs = try {
                Files.readAttributes(installDir.toAbsolutePath().normalize(), BasicFileAttributes::class.java)
            } catch (x: FileSystemException) {
                throw e
            }
            if (!attrs.isDirectory) return@withContext null
            throw e
        }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val (metadata, body) = SkillLoader.extractFrontmatter(text)
        val name = metadata["name"] as? String
        require(!name.isNullOrBlank()) { "SKILL.md at $path missing required 'name' field" }
        fun values(key: String): Set<String> = when (val value = metadata[key]) {
            is List<*> -> value.filterIsInstance<String>().toSet()
            is String -> setOf(value)
            else -> emptySet()
        }
        SkillSnapshot(
            SkillInfo(name, metadata["description"] as? String, path, body.trim(), values("tags"), values("examples")),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            metadata["version"]?.toString()?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_VERSION
        )
    }

    /** Explicit reconciliation always hashes, even if size and mtime were preserved. */
    suspend fun driftOf(entry: SkillCatalogEntry): SkillDrift {
        val snapshot = snapshotOf(entry) ?: return SkillDrift.Missing
        return if (snapshot.checksum == entry.checksum) SkillDrift.None
        else SkillDrift.Content(snapshot.checksum, snapshot.version)
    }

    suspend fun declaredVersionOf(entry: SkillCatalogEntry): String =
        snapshotOf(entry)?.version ?: SkillCatalogEntry.DEFAULT_VERSION

    suspend fun checksumOf(entry: SkillCatalogEntry): String? = snapshotOf(entry)?.checksum
    suspend fun checksumOf(installDir: Path): String? = snapshotOf(installDir)?.checksum

    companion object { const val SKILL_FILE_NAME = "SKILL.md" }
}
