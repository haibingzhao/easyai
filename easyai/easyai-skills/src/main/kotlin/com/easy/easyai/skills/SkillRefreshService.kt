package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.nio.file.Path

data class RefreshOutcome(
    val delta: RegistryDelta,
    val owner: String,
    val claimed: Int,
    val submitted: Int = 0,
    val summary: ReconcileSummary?,
    val claimFailed: Int = 0,
    val unclaimed: Int = 0
)

/** Startup and request refresh compare the whole registered source set, never only addedKeys. */
class SkillRefreshService(
    private val registry: SkillRegistry,
    private val catalog: AsyncSkillCatalogStore,
    private val syncService: SkillCatalogSyncService,
    private val indexer: SkillIndexer,
    private val config: SkillConfig = SkillConfig()
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mutex = Mutex()

    init { syncService.bindRegistry(registry) }

    suspend fun reconcileAllOwners(): ReconcileSummary? = mutex.withLock {
        try {
            val existing = catalog.listAll()
            registry.rescan(projectRoots(existing))
            val claims = syncService.claimUnclaimed(registry.all(), requestedUserId = null)
            val owners = catalog.listDistinctUserIds()
            val summary = indexer.reconcileByDrift(owners)
            summary.copy(failed = summary.failed + claims.failed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Skill startup reconciliation could not complete: {}", e.message)
            null
        }
    }

    suspend fun refreshFor(requestedUserId: String?, extraWorkDir: Path?): RefreshOutcome = mutex.withLock {
        // Fail closed before scanning/claiming when the owner inventory is unavailable.
        val existing = catalog.listAll()
        val delta = registry.rescan(projectRoots(existing) + setOfNotNull(extraWorkDir))
        val owner = requestedUserId?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_USER_ID
        val claims = syncService.claimUnclaimed(registry.all(), owner, extraWorkDir)
        val owners = catalog.listDistinctUserIds()
        val summary = indexer.reconcileByDrift(owners)
        RefreshOutcome(delta, owner, claims.claimed, summary.submitted, summary, claims.failed, claims.unclaimed)
    }

    /** Use from a lifecycle-managed, cancellable background loop; preserves the same coordination boundary. */
    suspend fun reconcilePending(): ReconcileSummary = mutex.withLock {
        indexer.reconcilePending()
    }

    private fun projectRoots(rows: List<SkillCatalogEntry>): Set<Path> = rows.mapNotNull { row ->
        try {
            val (scope, path) = SkillScopeResolver.resolve(row, config)
            path.takeIf { scope == SkillScope.PROJECT }
        } catch (e: IllegalArgumentException) {
            // Unknown sources remain unclaimed/quarantined; they are not promoted to GLOBAL.
            logger.warn("Cannot derive project root of skill '{}': {}", row.name, e.message)
            null
        }
    }.toSet()
}
