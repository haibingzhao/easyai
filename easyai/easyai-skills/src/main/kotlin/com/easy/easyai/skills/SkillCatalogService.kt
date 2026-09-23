package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.core.skill.SkillSyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

/**
 * One catalog row as the management surface needs to see it.
 *
 * @property scope granularity derived from the install path, never stored redundantly
 * @property installedOnDisk whether the SKILL.md is still there; false means reconciliation will
 *   delist the row on the next pass, so the UI can warn before that happens
 */
data class SkillCatalogView(
    val entry: SkillCatalogEntry,
    val scope: SkillScope,
    val projectPath: Path?,
    val installedOnDisk: Boolean
)

/** Outcome of an enable/disable toggle. */
sealed interface SkillToggleResult {
    /**
     * @property indexSynced false when the catalog row changed but the retrieval index could not
     *   be updated; the row is authoritative, so the skill stays hidden from search until
     *   reconciliation catches up
     */
    data class Applied(val name: String, val enabled: Boolean, val indexSynced: Boolean) : SkillToggleResult

    data class Rejected(val reason: String) : SkillToggleResult
}

/**
 * Management entry point over the `skill` catalog table.
 *
 * Exists so no caller can update the table and the index separately: [setEnabled] flips the row
 * and adds or removes the index document in one call, which is what keeps "disabled" from becoming
 * a lie the system prompt or `skill_search` still tells.
 */
class SkillCatalogService(
    private val catalog: AsyncSkillCatalogStore?,
    private val indexer: SkillIndexer,
    private val skillStore: SkillStore?,
    private val config: SkillConfig
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Every row owned by [SkillOwnerContext.userId], enriched with its granularity and freshness. */
    suspend fun list(owner: SkillOwnerContext): List<SkillCatalogView> {
        val store = catalog ?: return emptyList()
        val userId = owner.userId ?: SkillCatalogEntry.DEFAULT_USER_ID
        val rows = store.listByUser(userId)
        if (rows.isEmpty()) return emptyList()
        // One IO-dispatcher hop for the whole page instead of one per row: `installedOnDisk` is a stat call
        // and this method is called from Netty event-loop threads via the REST controller.
        return withContext(Dispatchers.IO) { rows.map { viewOf(it) } }
    }

    /** One row from this owner's point of view; null when the user does not own it. */
    suspend fun find(name: String, owner: SkillOwnerContext): SkillCatalogView? {
        val store = catalog ?: return null
        val userId = owner.userId ?: SkillCatalogEntry.DEFAULT_USER_ID
        val row = SkillOwnership.resolveRow(store, name, userId, owner.projectPath, config) ?: return null
        return withContext(Dispatchers.IO) { viewOf(row) }
    }

    /**
     * Enable or disable one skill, keeping the catalog row and the index in step.
     *
     * Enabling re-indexes from disk (so a skill edited while disabled publishes its new text);
     * disabling removes the document but keeps the row, which is where provenance lives.
     *
     * The row is flipped **first**, then the index is asked to catch up: [SkillIndexer.synchronize] can
     * legitimately fail (missing SKILL.md, backend outage) and the row must not stay behind that
     * failure — otherwise the API says "applied" while `load_skill` still refuses the skill.
     * `indexSynced` reports only the index side; the row is the source of truth.
     *
     * The target row is [SkillOwnership.resolveRow]'s pick for [owner]'s granularity — the same
     * winner `load_skill` would serve — and every write addresses it by primary key, so a toggle
     * can never hit a same-named skill of another project.
     */
    suspend fun setEnabled(name: String, owner: SkillOwnerContext, enabled: Boolean): SkillToggleResult {
        val store = catalog
            ?: return SkillToggleResult.Rejected("Skill catalog is not available: enable easyai.r2dbc.enabled")
        val userId = owner.userId ?: SkillCatalogEntry.DEFAULT_USER_ID
        val row = SkillOwnership.resolveRow(store, name, userId, owner.projectPath, config)
            ?: return SkillToggleResult.Rejected("Skill '$name' is not installed for user '$userId'")
        if (owner.userId.isNullOrBlank() || row.userId != userId || row.userId == SkillCatalogEntry.DEFAULT_USER_ID) {
            return SkillToggleResult.Rejected("Shared skills are read-only")
        }
        val flipped = try {
            if (enabled) indexer.prepareEnable(row) else store.setEnabled(row.id, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to toggle '{}' for '{}': {}", name, userId, e.message)
            false
        }
        if (!flipped) {
            return SkillToggleResult.Rejected("Skill content is unavailable or the catalog changed concurrently")
        }
        val summary = indexer.synchronize(row, await = false)
        val latest = store.findById(row.id)
        val synced = summary.failed == 0 && latest != null && latest.enabled == enabled &&
            latest.syncState == (if (enabled) SkillSyncState.SYNCED else SkillSyncState.ABSENT)
        logger.info("Skill '{}' of user '{}' enabled={} (indexSynced={})", name, userId, enabled, synced)
        return SkillToggleResult.Applied(name = name, enabled = enabled, indexSynced = synced)
    }

    private fun viewOf(entry: SkillCatalogEntry): SkillCatalogView {
        val (scope, projectPath) = SkillScopeResolver.resolve(entry, config)
        return SkillCatalogView(
            entry = entry,
            scope = scope,
            projectPath = projectPath,
            installedOnDisk = File(entry.installPath, SkillCatalogSyncService.SKILL_FILE_NAME).isFile
        )
    }
}
