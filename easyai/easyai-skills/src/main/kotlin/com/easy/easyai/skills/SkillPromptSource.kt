package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillStore
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Decides which skills go into the system prompt.
 *
 * Two independent reasons for a skill to be left out:
 * - **discovery moved**: with skill RAG wired up, `skill_search` finds skills per task, so the whole
 *   catalogue no longer has to ride along on every request. Suppression requires *both* the flag and
 *   an actual [SkillStore] bean — otherwise a downed RAG service would leave the agent with no skill
 *   discovery at all, which is why the caller passes [ragDiscoveryReady] instead of reading the flag.
 * - **the user disabled it**: a row with `enabled=false` must not be advertised, even while the full
 *   list is still injected from the registry.
 *
 * The disabled view is a cache rather than a per-request query: [skillsForPrompt] runs on the
 * prompt-render path, so it may not touch the database. [refreshVisibility] reloads it after startup
 * reconciliation and after every toggle, which are the only moments the table can change underneath.
 *
 * Disablement is tracked by **install path**, not by name: under project-granularity identity two
 * projects can own same-named skills, and switching one off must never hide the other.
 */
class SkillPromptSource(
    private val registry: SkillRegistry?,
    private val catalog: AsyncSkillCatalogStore?,
    private val injectIntoSystemPrompt: Boolean,
    private val ragEnabled: Boolean,
    private val ragDiscoveryReady: Boolean = ragEnabled
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Null until the first refresh: without a view we must not hide anything. Values are normalized install paths. */
    private val disabledByUser = AtomicReference<Map<String, Set<String>>?>(null)

    /** Whether the full catalogue should still be injected. */
    val fullInjectionActive: Boolean
        get() = injectIntoSystemPrompt && registry != null && !(ragEnabled && ragDiscoveryReady)

    /**
     * The `{name, description}` maps the prompt template renders.
     *
     * [userId] selects whose disablement applies; null means a request without an identity, which is
     * served by the default owner's rows — the same owner `backfillAll` assigns to existing skills.
     * [projectPath] restricts the view to the granularities that session can address (nearest-
     * ancestor PROJECT plus GLOBAL), the same visibility rule `load_skill` enforces — what the
     * prompt advertises, the tool can serve.
     */
    fun skillsForPrompt(userId: String? = null, projectPath: Path? = null): List<Map<String, Any?>> {
        val reg = registry ?: return emptyList()
        if (!fullInjectionActive) return emptyList()
        val disabled = disabledByUser.get()
        val visible = reg.visibleFor(projectPath)
            .filter { !it.description.isNullOrBlank() }
            .let { skills ->
                if (disabled.isNullOrEmpty()) {
                    skills
                } else {
                    val hidden = disabled[of(userId)] ?: emptySet()
                    skills.filterNot { it.location.parent?.let { dir -> SkillPaths.canonicalize(dir) } in hidden }
                }
            }
        return visible.map { mapOf<String, Any?>("name" to it.name, "description" to it.description) }
    }

    /**
     * Re-read which skills each owner has disabled.
     *
     * Failures keep the previous view rather than clearing it: a database blip must not silently
     * re-advertise skills the user switched off.
     */
    suspend fun refreshVisibility(): Boolean {
        val store = catalog ?: return false
        return try {
            val owners = (store.listDistinctUserIds() + SkillCatalogEntry.DEFAULT_USER_ID).distinct()
            val view = owners.associateWith { owner ->
                store.listByUser(owner).filterNot { it.enabled }.mapNotNull { SkillPaths.canonicalizeOrNull(it.installPath) }.toSet()
            }
            disabledByUser.set(view)
            logger.debug("Skill prompt visibility refreshed for {} owners", view.size)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not refresh skill prompt visibility, keeping the previous view: {}", e.message)
            false
        }
    }

    /** Catalog rows are keyed by their own user; anonymous requests inherit the default owner. */
    private fun of(userId: String?): String = userId?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_USER_ID
}
