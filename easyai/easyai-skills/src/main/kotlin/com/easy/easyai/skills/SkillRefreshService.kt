package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * What one request-driven refresh did, shaped for a tool message.
 *
 * @param delta what the re-scan changed in the in-memory snapshot
 * @param owner the tenant the rows were claimed under and reconciled for
 * @param claimed catalog rows inserted by this pass
 * @param submitted fresh documents handed to the index backend; submitting is not the same as being
 *   searchable, since the embedding runs in the background — see [refreshFor]
 * @param summary index reconciliation result for everything else this owner has, null when the
 *   catalog could not be read
 */
data class RefreshOutcome(
    val delta: RegistryDelta,
    val owner: String,
    val claimed: Int,
    val submitted: Int = 0,
    val summary: ReconcileSummary?
)

/**
 * The single implementation of *make what is on disk observable*: re-read the skill directories, claim
 * a catalog row for anything new, push drifted content to the search index, refresh the prompt view.
 *
 * Two entries because two different moments ask for the same chain:
 * - [reconcileAllOwners] runs once after startup, where no request says whose skills these are, so
 *   everything is claimed into the shared `system` tenant and every owner in the table is reconciled.
 * - [refreshFor] runs when an agent has just written a SKILL.md and needs it to be usable now. It
 *   claims into **the tenant the requester will be resolved to**, because that is the row the load
 *   gate will read back — see [SkillOwnership.tenantOf].
 *
 * The order inside a pass is the point: owners can only be enumerated *after* the backfill claimed the
 * skills that exist on disk, and the prompt view can only be refreshed *after* reconciliation may have
 * disabled rows.
 *
 * Content that really did not move costs one `stat`: [SkillCatalogSyncService.driftOf] memoises the
 * verified mtime per row, so calling this repeatedly is not repeated embedding traffic. Both entries
 * share one [Mutex] so two overlapping passes cannot write the same document twice.
 *
 * Which projects even *exist* is a database fact, not a filesystem one: both entries hydrate the
 * registry with [projectRootsFromCatalog] before scanning, so a project whose skill was claimed by
 * another session's refresh is loadable here without ever running `refresh_skills` locally.
 */
class SkillRefreshService(
    private val registry: SkillRegistry,
    private val catalog: AsyncSkillCatalogStore,
    private val syncService: SkillCatalogSyncService,
    private val indexer: SkillIndexer,
    private val promptSource: SkillPromptSource? = null,
    private val config: SkillConfig = SkillConfig()
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val mutex = Mutex()

    /**
     * Run one full pass over every owner and report what it cost.
     *
     * Visible so tests and an operator-triggered refresh can drive the same sequence the startup event
     * does; row-level failures are already absorbed by [SkillIndexer.reconcileByDrift], while a pass
     * that cannot reach the index at all lets the exception out — the caller decides whether that is
     * worth logging.
     */
    suspend fun reconcileAllOwners(): ReconcileSummary? = mutex.withLock {
        // Hydrate first: the table knows which projects have skills even when this server has
        // never scanned one. Rescanning before claiming keeps registry.all() the full disk truth.
        //
        // The registry's initial scan is lazy; whoever gets there first pays for it. In the common
        // RAG-enabled path that is *this* call, so the walk happens exactly once. When something
        // else (e.g. prompt rendering during bean creation) already triggered it, skip the rescan
        // unless the catalog names a project root the registry has never seen — the disk cannot
        // meaningfully change inside the few hundred milliseconds between bean wiring and
        // ApplicationReadyEvent, so paying for a second full walk is wasted work.
        val roots = projectRootsFromCatalog()
        val needsRescan = !registry.hasCompletedInitialScan() || roots.any { it !in registry.knownProjectRoots() }
        if (needsRescan) {
            registry.rescan(roots)
        } else {
            logger.debug("Skill startup pass reusing the registry's initial scan ({} known roots)", roots.size)
        }
        val claimed = try {
            syncService.backfillAll(registry.all())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Skill catalog backfill failed: {}", e.message)
            0
        }
        val owners = try {
            catalog.listDistinctUserIds()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not enumerate skill owners from the catalog: {}", e.message)
            return@withLock null
        }
        if (owners.isEmpty()) {
            logger.info("No skill catalog rows after backfill ({} claimed); nothing to reconcile", claimed)
            promptSource?.refreshVisibility()
            return@withLock ReconcileSummary()
        }
        val summary = indexer.reconcileByDrift(owners)
        // Reconciliation can disable rows, so the prompt view is refreshed from the result, not before.
        promptSource?.refreshVisibility()
        summary
    }

    /**
     * Re-read the disk for one requester and bring its own slice up to date.
     *
     * Only skills this pass *discovered* are claimed: everything else was already catalogued by the
     * startup pass, and re-declaring those rows for the current requester would hand one user the
     * ownership of directories another user installed.
     *
     * A row created below carries the checksum of the content on disk, so a drift comparison would
     * call it unchanged and never push it — the fresh rows are therefore handed to
     * [SkillIndexer.indexOne] directly. They are handed over **without waiting for the backend**: the
     * poll budget an install accepts (minutes, while a human watches a spinner) would destroy a running
     * conversation, and this pass holds the refresh mutex. The tool message therefore says *submitted*
     * and points at `load_skill`, which reads the file rather than the index.
     *
     * @param requestedUserId who is asking; null means a request without an identity
     * @param extraWorkDir the requesting session's project path, so a skill written into
     *   `<project>/.easyai/skills` is found even when the server was started elsewhere; the DB-known
     *   project roots ride along so one session's refresh also heals everyone else's snapshot
     */
    suspend fun refreshFor(requestedUserId: String?, extraWorkDir: Path?): RefreshOutcome = mutex.withLock {
        val delta = registry.rescan(projectRootsFromCatalog() + setOfNotNull(extraWorkDir))
        val owner = SkillOwnership.tenantOf(catalog, requestedUserId).userId
        val addedKeys = delta.addedKeys.toSet()
        // Look up only the added keys instead of filtering the whole registry: each `resolve()` is
        // a path walk plus a system-property read, and the registry can hold hundreds of skills
        // while `addedKeys` is typically one or two entries.
        val fresh = addedKeys.mapNotNull { key -> registry.get(key.name, key.projectPath) }
        val claimed = if (fresh.isEmpty()) 0 else try {
            syncService.backfillAll(fresh, owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Skill catalog backfill failed for '{}': {}", owner, e.message)
            0
        }
        var submitted = 0
        for (skill in fresh) {
            val row = rowFor(skill, owner) ?: continue
            if (!row.enabled) continue
            // No `await`: this runs inside a conversation turn. A write that does not land is logged and
            // carried on past, because the row and the file are what matter and `load_skill` reads those.
            val pushed = try {
                Result.success(indexer.indexOne(row, indexer.scopeOf(row), indexer.ownerOf(row)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (pushed.getOrDefault(false)) submitted++ else logger.warn(
                "Skill '{}' was catalogued but its index write did not land: {}",
                skill.name, pushed.exceptionOrNull()?.message ?: "the backend refused the document"
            )
        }
        // Reconcile both this requester's rows and the shared `system` bucket: startup claims
        // everything under `system`, so a user editing a GLOBAL skill must not leave the row that
        // actually holds it unreconciled just because `tenantOf` resolved the caller to themselves.
        val reconcileOwners = listOf(owner, SkillCatalogEntry.DEFAULT_USER_ID).distinct()
        val summary = try {
            indexer.reconcileByDrift(reconcileOwners)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not reconcile the skill index of '{}': {}", reconcileOwners, e.message)
            null
        }
        promptSource?.refreshVisibility()
        logger.info(
            "Skill refresh: owner={} added={} removed={} claimed={} submitted={} reindexed={} delisted={}",
            owner, delta.added, delta.removed, claimed, submitted,
            summary?.reindexed ?: 0, summary?.delisted ?: 0
        )
        RefreshOutcome(
            delta = delta, owner = owner, claimed = claimed,
            submitted = submitted, summary = summary
        )
    }

    /**
     * The catalog rows of every project this deployment knows about, read back from the table.
     *
     * "Which projects exist" is a DB fact: a skill claimed while working in project A must be
     * loadable from a session in project B without B ever running `refresh_skills`. A catalog that
     * cannot be read degrades to the empty set — the pass then scans what it always scanned
     * (config paths, home, work dir) and startup proceeds, because hydrating is an enhancement,
     * never a prerequisite.
     *
     * One [AsyncSkillCatalogStore.listAll] round trip rather than `listDistinctUserIds` followed by
     * a `listByUser` per owner: the derivation is a startup hot path and both queries touch the
     * same rows anyway.
     */
    private suspend fun projectRootsFromCatalog(): Set<Path> {
        val rows = try {
            catalog.listAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Skill roots hydration skipped: cannot read the catalog: {}", e.message)
            return emptySet()
        }
        val roots = LinkedHashSet<Path>()
        for (row in rows) {
            val (scope, projectPath) = SkillScopeResolver.resolve(row, config)
            if (scope == SkillScope.PROJECT && projectPath != null) roots.add(projectPath)
        }
        return roots
    }

    /** The row [backfillAll] just claimed for [skill]: same name, owner, and install directory. */
    private suspend fun rowFor(skill: SkillInfo, owner: String): SkillCatalogEntry? {
        val installDir = skill.location.parent?.let { SkillPaths.canonicalize(it) } ?: return null
        val rows = try {
            catalog.listByName(skill.name, owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not look up the catalog row of '{}' for '{}': {}", skill.name, owner, e.message)
            return null
        }
        return rows.firstOrNull { SkillPaths.canonicalizeOrNull(it.installPath) == installDir }
    }
}
