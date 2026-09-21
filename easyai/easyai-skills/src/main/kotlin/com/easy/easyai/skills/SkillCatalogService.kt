package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillStore
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
    private val config: SkillConfig,
    private val promptSource: SkillPromptSource? = null
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
     * The row is flipped **first**, then the index is asked to catch up: [SkillIndexer.indexOne] can
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
        val (scope, rowProjectPath) = SkillScopeResolver.resolve(row, config)
        val scopedOwner = SkillOwnerContext(userId, if (scope == SkillScope.PROJECT) rowProjectPath else null)
        // Row first: this is the authoritative state the load gate reads back.
        val flipped = try {
            store.setEnabled(row.id, enabled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to toggle '{}' for '{}': {}", name, userId, e.message)
            false
        }
        if (!flipped) {
            return SkillToggleResult.Rejected("Failed to update the catalog row of '$name'")
        }
        // Then the index: a failure here is reported via indexSynced=false but does not roll back the row,
        // because the row is what `load_skill` and the prompt view consult; the next reconciliation catches up.
        val synced = if (skillStore == null) {
            false
        } else if (enabled) {
            indexer.indexOne(row.copy(enabled = true), scope, scopedOwner, await = true)
        } else {
            indexer.removeOne(row.copy(enabled = false), removeCatalogRow = false)
        }
        publishVisibility()
        logger.info("Skill '{}' of user '{}' enabled={} (indexSynced={})", name, userId, enabled, synced)
        return SkillToggleResult.Applied(name = name, enabled = enabled, indexSynced = synced)
    }

    /**
     * Hand the new disablement to the prompt view.
     *
     * Without this a skill switched off in the UI would keep being advertised until the next restart,
     * because the prompt path reads a cache instead of querying the table per request.
     */
    private suspend fun publishVisibility() {
        promptSource?.refreshVisibility()
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
