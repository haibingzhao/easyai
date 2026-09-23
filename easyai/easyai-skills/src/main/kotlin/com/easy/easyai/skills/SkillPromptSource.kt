package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import java.nio.file.Path

/** Fresh, fail-closed model visibility. Catalog failures propagate to the request boundary. */
class SkillPromptSource(
    registry: SkillRegistry?,
    catalog: AsyncSkillCatalogStore?,
    private val injectIntoSystemPrompt: Boolean,
    private val ragEnabled: Boolean,
    private val ragDiscoveryReady: Boolean = ragEnabled,
    config: SkillConfig = SkillConfig()
) {
    private val modelView = registry?.let { SkillModelView(it, catalog, config) }

    /** Only the injection switch, not a process-wide assertion that every tenant's index is ready. */
    val fullInjectionActive: Boolean
        get() = injectIntoSystemPrompt && modelView != null

    /**
     * Suppression requires both a usable search tool in this agent and a synchronized effective
     * catalog. A SkillStore bean alone says nothing about readiness for this user/project.
     */
    suspend fun skillsForPrompt(
        userId: String? = null,
        projectPath: Path? = null,
        allowedSkillNames: List<String> = emptyList(),
        skillSearchAvailable: Boolean = false
    ): List<Map<String, Any?>> {
        val view = modelView ?: return emptyList()
        if (!fullInjectionActive) return emptyList()
        val skills = view.list(userId, projectPath, allowedSkillNames)
        if (ragEnabled && ragDiscoveryReady && skillSearchAvailable && skills.all { view.indexReady(it) }) {
            return emptyList()
        }
        return skills.filter { !it.skill.description.isNullOrBlank() }.map {
            mapOf("name" to it.skill.name, "description" to it.skill.description)
        }
    }

    /**
     * Names of the effective model view for this user/project, independent of the prompt-injection
     * switch and of RAG suppression: the default agent's `load_skill` whitelist derives from here,
     * never from the (possibly suppressed) skillsData rendered into the prompt.
     */
    suspend fun effectiveNames(userId: String?, projectPath: Path?): List<String> =
        modelView?.listEffective(userId, projectPath)?.map { it.skill.name } ?: emptyList()
}
