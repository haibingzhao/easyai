package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillDeleteResult
import com.easy.easyai.core.skill.SkillDocumentState
import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.core.skill.SkillSyncState
import com.easy.easyai.core.skill.SkillSyncUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.nio.file.Path

data class ReconcileSummary(
    val owners: Int = 0,
    val rows: Int = 0,
    val unchanged: Int = 0,
    val delisted: Int = 0,
    val failed: Int = 0,
    val submitted: Int = 0,
    val confirmed: Int = 0,
    val pending: Int = 0,
    val updated: Int = 0
) {
    val backendWrites: Int get() = submitted + delisted
}

/** Single-process recoverable projection; all remote operations share installation locks. */
class SkillIndexer(
    private val skillStore: SkillStore?,
    private val catalog: AsyncSkillCatalogStore?,
    private val syncService: SkillCatalogSyncService,
    private val config: SkillConfig,
    private val indexConcurrency: Int = DEFAULT_INDEX_CONCURRENCY
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun reconcileByDrift(userIds: List<String>): ReconcileSummary {
        val store = catalog ?: return ReconcileSummary()
        // A failed list is not an empty catalog. No claims or visibility publication may follow it.
        val rows = userIds.distinct().flatMap { store.listByUser(it) }
        return reconcileRows(rows, force = true).copy(owners = userIds.distinct().size)
    }

    /** Lifecycle owner supplies the cancellable loop. Bounded due-row inspection also heals remote loss. */
    suspend fun reconcilePending(limit: Int = 64): ReconcileSummary {
        val store = catalog ?: return ReconcileSummary()
        val now = System.currentTimeMillis()
        val rows = store.listAll().filter { (it.nextAttemptAt ?: 0L) <= now }
            .sortedWith(compareBy({ it.nextAttemptAt ?: 0L }, { it.id })).take(limit.coerceIn(1, 1024))
        return reconcileRows(rows, force = false).copy(owners = rows.map { it.userId }.distinct().size)
    }

    private suspend fun reconcileRows(rows: List<SkillCatalogEntry>, force: Boolean): ReconcileSummary = coroutineScope {
        val results = rows.chunked(indexConcurrency.coerceIn(1, 32)).flatMap { batch ->
            batch.map { row -> async { reconcile(row, force) } }.awaitAll()
        }
        ReconcileSummary(
            rows = rows.size, unchanged = results.sumOf { it.unchanged },
            confirmed = results.sumOf { it.confirmed },
            submitted = results.sumOf { it.submitted }, delisted = results.sumOf { it.delisted },
            failed = results.sumOf { it.failed }, pending = results.sumOf { it.pending }, updated = results.sumOf { it.updated }
        )
    }

    /** Parse and publish before atomically enabling the exact bytes; a concurrent disable wins the CAS. */
    suspend fun prepareEnable(entry: SkillCatalogEntry): Boolean = syncService.withInstallation(entry.installPath) {
        val store = catalog ?: return@withInstallation false
        val row = store.findById(entry.id) ?: return@withInstallation false
        if (row.revision != entry.revision) return@withInstallation false
        SkillScopeResolver.resolve(row, config)
        val snapshot = syncService.snapshotOf(row) ?: return@withInstallation false
        require(snapshot.info.name == row.name) { "Skill name differs from its catalog identity" }
        syncService.publish(snapshot)
        store.updateContent(row.id, row.revision, snapshot.checksum, snapshot.version, enable = true)
    }

    suspend fun synchronize(entry: SkillCatalogEntry, await: Boolean = false): ReconcileSummary =
        reconcile(entry, force = true, await = await)

    private suspend fun reconcile(entry: SkillCatalogEntry, force: Boolean, await: Boolean = false): ReconcileSummary =
        syncService.withInstallation(entry.installPath) {
            val store = catalog ?: return@withInstallation ReconcileSummary()
            var row = store.findById(entry.id) ?: return@withInstallation ReconcileSummary()
            if (!force && (row.nextAttemptAt ?: 0L) > System.currentTimeMillis()) {
                return@withInstallation ReconcileSummary(pending = if (isPending(row)) 1 else 0)
            }
            var updated = 0
            try {
                val duplicate = store.listAll().any {
                    it.id != row.id && SkillPaths.canonicalizeOrNull(it.installPath) == SkillPaths.canonicalizeOrNull(row.installPath)
                }
                check(!duplicate) { "Installation claimed by multiple identities; manual resolution required" }
                validateIdentity(row)
                val snapshot = if (row.enabled) syncService.snapshotOf(row) else try {
                    syncService.snapshotOf(row)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Broken disabled content must not prevent remote cleanup.
                    null
                }
                if (snapshot == null && row.enabled) {
                    store.setEnabled(row.id, false)
                    row = store.findById(row.id) ?: return@withInstallation ReconcileSummary()
                }
                if (snapshot != null) {
                    require(snapshot.info.name == row.name) { "Skill name differs from its catalog identity" }
                    if (snapshot.checksum != row.checksum) {
                        syncService.publish(snapshot)
                        if (!store.updateContent(row.id, row.revision, snapshot.checksum, snapshot.version)) {
                            return@withInstallation ReconcileSummary(pending = 1)
                        }
                        row = requireNotNull(store.findById(row.id))
                        updated = 1
                    } else if (row.enabled) {
                        syncService.publish(snapshot)
                    }
                }
                validateIdentity(row)
                val remote = skillStore ?: return@withInstallation ReconcileSummary(pending = if (isPending(row)) 1 else 0, updated = updated)
                if (!row.enabled || snapshot == null) {
                    return@withInstallation deleteCurrent(row, remote).copy(updated = updated)
                }
                val state = remote.inspect(row.name, addressScope(row.projectHash), addressOwner(row))
                if (state is SkillDocumentState.Processed && state.checksum == row.checksum) {
                    val already = row.syncState == SkillSyncState.SYNCED && row.indexedChecksum == row.checksum
                    if (!complete(row, SkillSyncState.SYNCED, row.checksum, VERIFY_MS)) {
                        return@withInstallation compensate(row, remote).copy(updated = updated)
                    }
                    return@withInstallation ReconcileSummary(
                        unchanged = if (already) 1 else 0, confirmed = if (already) 0 else 1, updated = updated
                    )
                }
                // Re-submit pending/failed/missing documents with the same deterministic identity.
                // This also advances an unchanged upload whose original indexing request was lost.
                val result = remote.submit(listOf(document(row, snapshot)), addressScope(row.projectHash), addressOwner(row), await)
                    .singleOrNull()?.state ?: SkillDocumentState.Failed("Missing per-document submission result")
                if (store.findById(row.id)?.revision != row.revision) {
                    return@withInstallation compensate(row, remote).copy(updated = updated)
                }
                when (result) {
                    is SkillDocumentState.Processed -> {
                        if (result.checksum != row.checksum) {
                            failure(row, "Remote processed a different or unreported checksum").copy(submitted = 1, updated = updated)
                        } else if (complete(row, SkillSyncState.SYNCED, row.checksum, VERIFY_MS)) {
                            ReconcileSummary(submitted = 1, confirmed = 1, updated = updated)
                        } else compensate(row, remote).copy(submitted = 1, updated = updated)
                    }
                    is SkillDocumentState.Submitted -> {
                        if (complete(row, SkillSyncState.SUBMITTED, row.indexedChecksum, RETRY_MS)) {
                            ReconcileSummary(submitted = 1, pending = 1, updated = updated)
                        } else compensate(row, remote).copy(submitted = 1, updated = updated)
                    }
                    is SkillDocumentState.Failed -> failure(row, result.error).copy(updated = updated)
                    SkillDocumentState.Absent -> failure(row, "Submitted document is absent").copy(updated = updated)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Skill reconciliation failed for '{}': {}", row.name, e.message)
                failure(row, e.message ?: "Skill reconciliation failed").copy(updated = updated)
            }
        }

    private suspend fun validateIdentity(row: SkillCatalogEntry) {
        try {
            SkillScopeResolver.resolve(row, config)
        } catch (e: IllegalArgumentException) {
            // Configuration/source changes do not authorize a move. Only the persisted, verifiable
            // address may be cleaned; the newly classified slice must remain untouched.
            skillStore?.let { remote ->
                val result = remote.ensureAbsent(row.name, addressScope(row.projectHash), addressOwner(row))
                check(result is SkillDeleteResult.Absent) { "Invalid identity slice cleanup is pending" }
            }
            throw e
        }
    }

    private suspend fun deleteCurrent(row: SkillCatalogEntry, remote: SkillStore): ReconcileSummary {
        return when (val result = remote.ensureAbsent(row.name, addressScope(row.projectHash), addressOwner(row))) {
            SkillDeleteResult.Absent -> {
                if (!complete(row, SkillSyncState.ABSENT, null, VERIFY_MS)) return ReconcileSummary(pending = 1)
                if (row.syncState == SkillSyncState.ABSENT) ReconcileSummary(unchanged = 1)
                else ReconcileSummary(delisted = 1)
            }
            is SkillDeleteResult.Failed -> failure(row, result.error)
        }
    }

    /** A CAS alone cannot undo a remote request that finished after disable. */
    private suspend fun compensate(submitted: SkillCatalogEntry, remote: SkillStore): ReconcileSummary {
        val store = requireNotNull(catalog)
        val latest = store.findById(submitted.id) ?: return ReconcileSummary(failed = 1, pending = 1)
        val result = remote.ensureAbsent(submitted.name, addressScope(submitted.projectHash), addressOwner(submitted))
        if (result is SkillDeleteResult.Failed) return failure(latest, result.error)
        val state = if (latest.enabled) SkillSyncState.PENDING_INDEX else SkillSyncState.ABSENT
        if (!complete(latest, state, null, if (latest.enabled) RETRY_MS else VERIFY_MS)) return ReconcileSummary(pending = 1)
        return if (latest.enabled) ReconcileSummary(pending = 1) else ReconcileSummary(delisted = 1)
    }

    private suspend fun complete(row: SkillCatalogEntry, state: SkillSyncState, checksum: String?, delay: Long): Boolean =
        requireNotNull(catalog).updateSync(row.id, row.revision,
            SkillSyncUpdate(state, checksum, System.currentTimeMillis() + delay))

    private suspend fun failure(row: SkillCatalogEntry, error: String): ReconcileSummary {
        try {
            val state = if (!row.enabled) SkillSyncState.PENDING_DELETE
            else if (row.syncState == SkillSyncState.SUBMITTED) SkillSyncState.SUBMITTED else SkillSyncState.PENDING_INDEX
            val delay = if (row.lastError == null) RETRY_MS else MAX_RETRY_MS
            catalog?.updateSync(row.id, row.revision,
                SkillSyncUpdate(state, row.indexedChecksum, System.currentTimeMillis() + delay, error))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not persist skill retry for '{}': {}", row.name, e.message)
        }
        return ReconcileSummary(failed = 1, pending = 1)
    }

    private fun document(row: SkillCatalogEntry, snapshot: SkillSnapshot) = SkillEntry(
        key = SkillEntry.keyFor(row.name), name = row.name, description = snapshot.info.description.orEmpty(),
        tags = snapshot.info.tags.toList(), examples = snapshot.info.examples.toList(), content = snapshot.info.content,
        location = snapshot.info.location.toString(), origin = row.origin, checksum = snapshot.checksum
    )

    private fun addressScope(hash: String): SkillScope = if (hash.isEmpty()) SkillScope.GLOBAL else SkillScope.PROJECT

    private fun addressOwner(row: SkillCatalogEntry): SkillOwnerContext {
        val path = row.indexProjectPath?.let { Path.of(it) }
        check(if (row.projectHash.isEmpty()) path == null else
            path != null && SkillPaths.canonicalize(path) == row.indexProjectPath &&
                SkillScopeResolver.projectHashOf(path) == row.projectHash) {
            "Unrecoverable skill slice address"
        }
        return SkillOwnerContext(row.userId, path)
    }

    private fun isPending(row: SkillCatalogEntry): Boolean = row.syncState != SkillSyncState.SYNCED && row.syncState != SkillSyncState.ABSENT

    companion object {
        const val DEFAULT_INDEX_CONCURRENCY = 4
        private const val RETRY_MS = 5_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val VERIFY_MS = 300_000L
    }
}
