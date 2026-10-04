package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillCatalogEntry
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * What one `refresh_skills` pass did, shaped for the model: the publication delta, the
 * catalog/package reconciliation counters and the index summary.
 */
data class RefreshOutcome(
    val owners: List<String> = emptyList(),
    val delta: RegistryDelta? = null,
    val sync: SkillSyncOutcome? = null,
    val summary: ReconcileSummary? = null
)

/**
 * Orchestration layer over [SkillSyncService] (catalog + packages + disk) and [SkillIndexer]
 * (retrieval projection).
 *
 * Lock discipline: the sync phase runs under the per-owner mutex inside [SkillSyncService]; the
 * index phase runs **after** it is released, so indexer calls always re-acquire the same lock via
 * [SkillIndexer] and never nest.
 */
class SkillRefreshService(
    private val syncService: SkillSyncService,
    private val indexer: SkillIndexer
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Owners this process has already lazily reconciled; cleared for an owner when its sync fails. */
    private val syncedOwners = ConcurrentHashMap.newKeySet<String>()

    /** Full reconcile for the requester (and the shared layer): sync first, then re-index both owners. */
    suspend fun refreshFor(userId: String?): RefreshOutcome {
        val owners = ownersFor(userId)
        val sync = try {
            syncService.syncFor(userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Skill sync could not complete for {}: {}", owners, e.message)
            null
        }
        val summary = try {
            indexer.reconcileByDrift(owners)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Skill index reconciliation could not complete for {}: {}", owners, e.message)
            null
        }
        return RefreshOutcome(owners, sync?.delta, sync, summary)
    }

    /**
     * First-access lazy sync gate for request paths (login hook, `load_skill`, `skill_search`,
     * prompt build): the first call for an owner runs the full reconcile once per process; every
     * later call is a set lookup. A failed pass un-marks the owners so the next access retries.
     */
    suspend fun ensureSynced(userId: String?) {
        val owners = ownersFor(userId)
        val claimed = owners.filter { syncedOwners.add(it) }
        if (claimed.isEmpty()) return
        try {
            val sync = syncService.syncFor(userId)
            indexer.reconcileByDrift(owners)
            logger.info(
                "Lazy skill sync for {} claimed={} pushed={} restored={} backfilled={} " +
                    "skipped={} failed={} unclaimed={}",
                owners, sync.claimed, sync.pushed, sync.restored, sync.backfilled,
                sync.skipped, sync.failed, sync.unclaimed
            )
        } catch (e: CancellationException) {
            syncedOwners.removeAll(claimed.toSet())
            throw e
        } catch (e: Exception) {
            syncedOwners.removeAll(claimed.toSet())
            logger.warn("Lazy skill sync for {} failed; will retry on next access: {}", owners, e.message)
        }
    }

    /** Background retry loop for rows still waiting on their index projection. */
    suspend fun reconcilePending(): ReconcileSummary = indexer.reconcilePending()

    /** Add through the sync pipeline, then hand the new row to the index projection. */
    suspend fun addSkill(owner: String, name: String, sourceDir: Path): SkillAddResult =
            indexQuietly(syncService.addSkill(owner, name, sourceDir))

    /** Upload through the sync pipeline (staged off-tree), then hand the new row to the index projection. */
    suspend fun addUploaded(owner: String, name: String, upload: SkillUpload): SkillAddResult =
            indexQuietly(syncService.addUploaded(owner, name, upload))

    private suspend fun indexQuietly(result: SkillAddResult): SkillAddResult {
        if (result is SkillAddResult.Added) synchronizeQuietly(result.row)
        return result
    }

    /** Remove row + package + directory + registry entry, then delist the retrieval document. */
    suspend fun deleteSkill(owner: String, name: String): SkillCatalogEntry? {
        val row = syncService.deleteSkill(owner, name) ?: return null
        val remoteDelisted = try {
            indexer.delist(name, owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not delist skill '{}' of '{}': {}", name, owner, e.message)
            false
        }
        if (!remoteDelisted) logger.warn("Retrieval document for '{}/{}' may linger until reconciliation runs", owner, name)
        return row
    }

    private suspend fun synchronizeQuietly(row: SkillCatalogEntry) {
        try {
            indexer.synchronize(row, await = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Index submission for newly added '{}' failed; reconciliation will retry: {}", row.name, e.message)
        }
    }

    /** Owners one request touches: the shared layer plus the requester (once, for `system` itself). */
    fun ownersFor(userId: String?): List<String> {
        val owner = syncService.ownerOf(userId)
        return if (owner == SkillCatalogEntry.DEFAULT_USER_ID) listOf(owner)
        else listOf(SkillCatalogEntry.DEFAULT_USER_ID, owner)
    }
}
