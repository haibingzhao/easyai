package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Result of one drift-reconciliation pass, for logs and for the startup health check.
 *
 * [backendWrites] is the number this design is trying to keep at zero: a steady-state boot should
 * produce no HTTP traffic at all.
 */
data class ReconcileSummary(
    val owners: Int = 0,
    val rows: Int = 0,
    val unchanged: Int = 0,
    val reindexed: Int = 0,
    val delisted: Int = 0,
    val failed: Int = 0
) {
    /** Documents pushed to or removed from the retrieval backend. */
    val backendWrites: Int
        get() = reindexed + delisted
}

/**
 * Keeps the RAG skill index aligned with the `skill` catalog table and the files on disk.
 *
 * Reconciliation is **checksum-drift driven**: [reconcileByDrift] compares each row's persisted
 * fingerprint with the SKILL.md on disk and only talks to the backend for rows that actually
 * moved. The previous design held that hash map in process memory, so it was empty after every
 * restart and re-pushed every skill on every boot; the fingerprint now lives in the database, so
 * a stable installation costs `N` stat calls and zero requests.
 *
 * Write ordering is the invariant that keeps the three sources from drifting apart:
 * **disk → catalog row → index**. [indexOne] therefore refreshes the row before touching the
 * index and lets row-write failures propagate, so callers roll back the file they just wrote;
 * an index failure alone is only logged, because the next reconciliation will re-apply it.
 */
class SkillIndexer(
    private val skillStore: SkillStore?,
    private val catalog: AsyncSkillCatalogStore?,
    private val syncService: SkillCatalogSyncService,
    private val config: SkillConfig,
    private val indexConcurrency: Int = DEFAULT_INDEX_CONCURRENCY
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Reconcile every catalog row of every owner in [userIds].
     *
     * Owners come from the table, not from a request context — that is what makes per-user slices
     * addressable at startup. Each row is handled independently and failures are logged, never
     * propagated: one broken skill must not hold up application readiness.
     */
    suspend fun reconcileByDrift(userIds: List<String>): ReconcileSummary {
        if (skillStore == null && catalog == null) {
            logger.debug("Skill reconciliation skipped: neither index store nor catalog is available")
            return ReconcileSummary()
        }
        // Plain counters instead of `summary.copy(...)` per row: on a large catalog the copy chain
        // allocated one intermediate `ReconcileSummary` per row for no benefit.
        var rows = 0
        var unchanged = 0
        var reindexed = 0
        var delisted = 0
        var failed = 0
        for (userId in userIds) {
            val ownerRows = try {
                catalog?.listByUser(userId) ?: emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to list skill catalog rows for user '{}': {}", userId, e.message)
                failed++
                continue
            }
            rows += ownerRows.size
            for ((row, outcome) in reconcileRows(ownerRows)) {
                when (outcome) {
                    RowOutcome.UNCHANGED -> unchanged++
                    RowOutcome.REINDEXED -> reindexed++
                    RowOutcome.DELISTED -> delisted++
                    RowOutcome.FAILED -> {
                        logger.warn("Skill reconciliation failed for '{}' of user '{}'", row.name, userId)
                        failed++
                    }
                }
            }
        }
        val summary = ReconcileSummary(
            owners = userIds.size,
            rows = rows,
            unchanged = unchanged,
            reindexed = reindexed,
            delisted = delisted,
            failed = failed
        )
        logger.info(
            "Skill reconciliation: {} owners, {} rows, unchanged={}, reindexed={}, " +
                "delisted={}, failed={} (backend writes={})",
            summary.owners, summary.rows, summary.unchanged, summary.reindexed,
            summary.delisted, summary.failed, summary.backendWrites
        )
        return summary
    }

    /**
     * Index one skill and record it in the catalog, in that enforced order.
     *
     * The row is refreshed from disk first (checksum + declared version), so the table can never
     * describe content the index does not hold. A catalog write failure propagates on purpose:
     * install and create paths must roll back the files they already wrote.
     *
     * @param entry catalog row as the caller wants it persisted (owner/source/enabled included)
     * @param await true waits for the backend to finish indexing, so a just-installed skill is
     *   immediately findable by `skill_search`
     * @return true when the document reached the index backend
     */
    suspend fun indexOne(
        entry: SkillCatalogEntry,
        scope: SkillScope,
        owner: SkillOwnerContext,
        await: Boolean = false
    ): Boolean {
        val store = skillStore ?: return false
        val checksum = syncService.checksumOf(entry)
        if (checksum == null) {
            logger.warn("Cannot index skill '{}': SKILL.md is missing or unreadable at {}", entry.name, entry.installPath)
            return false
        }
        val refreshed = entry.copy(checksum = checksum, version = syncService.declaredVersionOf(entry))
        val persisted = catalog?.upsert(refreshed) ?: refreshed
        val document = entryOf(persisted) ?: return false
        val indexed = try {
            store.index(listOf(document), scope, owner, awaitIndexing = await)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Index loss is recoverable on the next reconciliation; the row and the file are what matter.
            logger.warn("Skill index write failed for '{}': {}", persisted.name, e.message)
            0
        }
        return indexed > 0
    }

    /**
     * Remove one skill from the index and take it out of the catalog.
     *
     * Addressed by the row itself, not by name: under (owner, name, project_hash) identity a name
     * alone can match several rows, and an uninstall must never touch a same-named skill of another
     * project. Scope and slice owner are derived from the row's install path.
     *
     * @param removeCatalogRow false for a temporary disable (the row survives with
     *   `enabled=false` and keeps its provenance), true for an uninstall
     * @return true when the index no longer serves the document (or no index is configured)
     */
    suspend fun removeOne(
        entry: SkillCatalogEntry,
        removeCatalogRow: Boolean = true
    ): Boolean {
        val store = skillStore
        val rowHandled = try {
            if (removeCatalogRow) {
                catalog?.delete(entry.id) ?: true
            } else {
                catalog?.setEnabled(entry.id, false) ?: true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to update the catalog row of '{}': {}", entry.name, e.message)
            false
        }
        // The row is gone or disabled: any memoised mtime for it is stale and must not survive,
        // otherwise a re-install with the same id could short-circuit `driftOf` to None.
        syncService.invalidate(entry.id)
        if (store == null) return rowHandled
        val indexRemoved = try {
            store.delete(entry.name, scopeOf(entry), ownerOf(entry))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to delete the index of '{}': {}", entry.name, e.message)
            false
        }
        return rowHandled && indexRemoved
    }

    /** Build the index document for one catalog row, read from the SKILL.md on disk. */
    suspend fun entryOf(entry: SkillCatalogEntry): SkillEntry? = withContext(Dispatchers.IO) {
        val skillFile = Path.of(entry.installPath).resolve(SkillCatalogSyncService.SKILL_FILE_NAME)
        runCatching { SkillLoader.parse(skillFile) }
            .onFailure { logger.warn("Failed to parse {}: {}", skillFile, it.message) }
            .getOrNull()
            ?.let { info ->
                SkillEntry(
                    key = SkillEntry.keyFor(info.name),
                    name = info.name,
                    description = info.description ?: "",
                    tags = info.tags.toList(),
                    examples = info.examples.toList(),
                    // The body is indexed alongside the frontmatter: descriptions alone recall poorly.
                    content = info.content,
                    location = info.location.toAbsolutePath().toString(),
                    origin = entry.origin
                )
            }
    }

    /** Slice owner of a catalog row: the row's own user plus the project it belongs to. */
    fun ownerOf(entry: SkillCatalogEntry): SkillOwnerContext {
        val (scope, projectPath) = SkillScopeResolver.resolve(entry, config)
        return SkillOwnerContext(entry.userId, if (scope == SkillScope.PROJECT) projectPath else null)
    }

    /** Catalog granularity of a catalog row, derived from its install path. */
    fun scopeOf(entry: SkillCatalogEntry): SkillScope = SkillScopeResolver.resolve(entry, config).first

    private enum class RowOutcome { UNCHANGED, REINDEXED, DELISTED, FAILED }

    /**
     * Reconcile the rows of one owner with at most [indexConcurrency] backend writes in flight.
     *
     * Bounded rather than unbounded because every drifted row is an `upsert` against a shared
     * EasyRAG pipeline: a first boot after `backfillAll` can otherwise push hundreds of documents at
     * once and turn a slow start into a 409 storm. Results stay in row order.
     */
    private suspend fun reconcileRows(rows: List<SkillCatalogEntry>): List<Pair<SkillCatalogEntry, RowOutcome>> {
        val width = indexConcurrency.coerceIn(1, MAX_INDEX_CONCURRENCY)
        return coroutineScope {
            // Await each window before opening the next one; launching every coroutine up front
            // would leave the configured width with no effect at all.
            rows.chunked(width)
                .flatMap { window -> window.map { row -> async { row to reconcileRow(row) } }.awaitAll() }
        }
    }

    private suspend fun reconcileRow(entry: SkillCatalogEntry): RowOutcome = try {
        when (val drift = syncService.driftOf(entry)) {
            SkillDrift.None -> RowOutcome.UNCHANGED
            is SkillDrift.Content -> applyContentDrift(entry, drift)
            // Disk is the source of truth for a local skill: a row whose SKILL.md is gone is
            // delisted, and a later scan re-claims it under the new path if the files come back.
            SkillDrift.Missing -> {
                delist(entry)
                RowOutcome.DELISTED
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.debug("Skill reconciliation error for '{}'", entry.name, e)
        RowOutcome.FAILED
    }

    private suspend fun applyContentDrift(
        entry: SkillCatalogEntry,
        drift: SkillDrift.Content
    ): RowOutcome {
        // A disabled row is not in the index; recording a fresh checksum here would make the next
        // reconcile see `None` and skip the re-index that a re-enable actually needs. Leave the
        // stored checksum behind so enabling picks up the drift and pushes the current bytes.
        if (!entry.enabled) {
            syncService.invalidate(entry.id)
            return RowOutcome.UNCHANGED
        }
        catalog?.updateChecksum(entry.id, drift.newChecksum, drift.newVersion)
        val store = skillStore ?: return RowOutcome.UNCHANGED
        val document = entryOf(entry.copy(checksum = drift.newChecksum, version = drift.newVersion))
            ?: return RowOutcome.FAILED
        val indexed = try {
            store.index(listOf(document), scopeOf(entry), ownerOf(entry))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Skill re-index failed for '{}': {}", entry.name, e.message)
            0
        }
        return if (indexed > 0) RowOutcome.REINDEXED else RowOutcome.FAILED
    }

    /** Take a row out of the index and mark it disabled, keeping the row for provenance. */
    private suspend fun delist(entry: SkillCatalogEntry) {
        val store = skillStore
        if (store != null) {
            try {
                store.delete(entry.name, scopeOf(entry), ownerOf(entry))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to delete the index of '{}': {}", entry.name, e.message)
            }
        }
        try {
            catalog?.setEnabled(entry.id, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to disable the catalog row of '{}': {}", entry.name, e.message)
        }
        syncService.invalidate(entry.id)
        logger.warn("Skill '{}' has no SKILL.md at {}; delisted", entry.name, entry.installPath)
    }

    companion object {
        /** Matches the concurrency the RAG store itself uses for document writes. */
        const val DEFAULT_INDEX_CONCURRENCY = 4

        private const val MAX_INDEX_CONCURRENCY = 32
    }
}
