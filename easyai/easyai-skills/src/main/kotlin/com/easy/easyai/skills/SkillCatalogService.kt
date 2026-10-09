package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillSyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * One catalog row as the management surface needs to see it.
 *
 * @param shared true when the row belongs to the read-only `system` layer
 * @param installedOnDisk whether the skill directory still carries a SKILL.md; false means the
 *   next sync restores it from the object-storage package, so the UI can say so before that happens
 */
data class SkillCatalogView(
    val entry: SkillCatalogEntry,
    val shared: Boolean,
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
 *
 * The row is flipped **first**, then the index is asked to catch up: [SkillIndexer.synchronize] can
 * legitimately fail (missing SKILL.md, backend outage) and the row must not stay behind that
 * failure — otherwise the API says "applied" while `load_skill` still refuses the skill.
 * `indexSynced` reports only the index side; the row is the source of truth.
 */
class SkillCatalogService(
    private val catalog: AsyncSkillCatalogStore?,
    private val indexer: SkillIndexer
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** The requester's own rows plus the shared `system` rows; same names appear once, own first. */
    suspend fun list(owner: SkillOwnerContext): List<SkillCatalogView> =
        listForOwners(listOfNotNull(owner.userId))

    /**
     * Group-aware [list] over an ordered owner set (self → group → system): every row whose name is
     * not shadowed by a higher-priority owner, own rows first. A member thus sees their group's skills
     * alongside their own, with the shared layer last.
     */
    suspend fun listForOwners(owners: Collection<String>): List<SkillCatalogView> {
        val store = catalog ?: return emptyList()
        val ordered = normalizeOwners(owners)
        val rows = store.listByOwners(ordered)
        if (rows.isEmpty()) return emptyList()
        // First owner in priority order claims each name; lower layers only fill the gaps.
        val winners = LinkedHashMap<String, SkillCatalogEntry>()
        for (o in ordered) {
            rows.filter { it.userId == o }.forEach { winners.putIfAbsent(it.name, it) }
        }
        // One IO-dispatcher hop for the whole page instead of one per row: `installedOnDisk` is a
        // stat call and this method is called from event-loop threads via the REST controller.
        return withContext(Dispatchers.IO) { winners.values.map { viewOf(it) } }
    }

    /** One row from this owner's point of view: their own first, then the shared layer. */
    suspend fun find(name: String, owner: SkillOwnerContext): SkillCatalogView? {
        val store = catalog ?: return null
        val userId = owner.userId?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_USER_ID
        val own = store.findByName(userId, name)
        val system = if (userId == SkillCatalogEntry.DEFAULT_USER_ID) null
        else store.findByName(SkillCatalogEntry.DEFAULT_USER_ID, name)
        val row = own ?: system ?: return null
        return withContext(Dispatchers.IO) { viewOf(row) }
    }

    /**
     * Enable or disable one skill, keeping the catalog row and the index in step.
     *
     * Enabling re-indexes from disk (so a skill edited while disabled publishes its new text);
     * disabling removes the document but keeps the row, which is where provenance lives.
     *
     * Only the requester's **own** rows are toggleable: a name matched solely in the shared layer
     * is read-only for every regular user (admins toggle it by acting as the `system` owner).
     */
    suspend fun setEnabled(name: String, owner: SkillOwnerContext, enabled: Boolean): SkillToggleResult {
        val store = catalog
            ?: return SkillToggleResult.Rejected("Skill catalog is not available: enable easyai.r2dbc.enabled")
        val userId = owner.userId?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_USER_ID
        val row = store.findByName(userId, name)
            ?: return SkillToggleResult.Rejected(
                if (userId == SkillCatalogEntry.DEFAULT_USER_ID || store.findByName(SkillCatalogEntry.DEFAULT_USER_ID, name) == null)
                    "Skill '$name' is not installed for user '$userId'"
                else "Shared skills are read-only"
            )
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

    /** Distinct non-blank owners in priority order, with `system` appended as the final fallback. */
    private fun normalizeOwners(owners: Collection<String>): List<String> {
        val system = SkillCatalogEntry.DEFAULT_USER_ID
        val cleaned = owners.filter { it.isNotBlank() }.distinct()
        val base = if (cleaned.isEmpty()) listOf(system) else cleaned
        return if (system in base) base else base + system
    }

    private fun viewOf(entry: SkillCatalogEntry): SkillCatalogView {
        val installed = runCatching {
            Files.isRegularFile(Path.of(entry.installPath).resolve(SkillPaths.SKILL_FILE_NAME))
        }.getOrDefault(false)
        return SkillCatalogView(
            entry = entry,
            shared = entry.userId == SkillCatalogEntry.DEFAULT_USER_ID,
            installedOnDisk = installed
        )
    }
}
