package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore

/**
 * Fresh, fail-closed model visibility. Catalog failures propagate to the request boundary.
 *
 * @param firstAccessSync lazy-sync gate invoked before the first read of a process for one user
 *   ([SkillRefreshService.ensureSynced]); null when no sync layer is wired.
 * @param directInjectMaxSkills owners with an effective catalog of at most this size keep the full
 *   listing injected even with the RAG index ready; 0 (default) suppresses whenever the index is
 *   usable — the pre-threshold behaviour — and wiring overrides it from configuration.
 */
class SkillPromptSource(
    registry: SkillRegistry?,
    catalog: AsyncSkillCatalogStore?,
    private val injectIntoSystemPrompt: Boolean,
    private val ragEnabled: Boolean,
    private val ragDiscoveryReady: Boolean = ragEnabled,
    private val firstAccessSync: (suspend (Collection<String>) -> Unit)? = null,
    private val directInjectMaxSkills: Int = 0
) {
    private val modelView = registry?.let { SkillModelView(it, catalog) }

    /** Only the injection switch, not a process-wide assertion that every tenant's index is ready. */
    val fullInjectionActive: Boolean
        get() = injectIntoSystemPrompt && modelView != null

    /**
     * Suppression requires both a usable search tool in this agent and a synchronized effective
     * catalog. A SkillStore bean alone says nothing about readiness for this user. Scale-gated: an
     * owner with few effective skills keeps the direct listing regardless of index readiness.
     */
    suspend fun skillsForPrompt(
        userId: String? = null,
        allowedSkillNames: List<String> = emptyList(),
        skillSearchAvailable: Boolean = false
    ): List<Map<String, Any?>> = skillsForPromptOwners(listOfNotNull(userId), allowedSkillNames, skillSearchAvailable)

    /** Group-aware [skillsForPrompt] over an ordered owner set (self → group → system). */
    suspend fun skillsForPromptOwners(
        owners: Collection<String>,
        allowedSkillNames: List<String> = emptyList(),
        skillSearchAvailable: Boolean = false
    ): List<Map<String, Any?>> {
        val view = modelView ?: return emptyList()
        firstAccessSync?.invoke(owners)
        if (!fullInjectionActive) return emptyList()
        val effective = view.listEffectiveForOwners(owners)
        val skills = if (allowedSkillNames.isEmpty()) emptyList()
        else effective.filter { it.skill.name in allowedSkillNames.toSet() }
        if (ragEnabled && ragDiscoveryReady && skillSearchAvailable &&
            effective.size > directInjectMaxSkills && skills.all { view.indexReady(it) }
        ) {
            return emptyList()
        }
        return skills.filter { !it.skill.description.isNullOrBlank() }.map {
            mapOf("name" to it.skill.name, "description" to it.skill.description)
        }
    }

    /**
     * Routing candidates for turn-level skill selection: whitelist ∩ effective catalog, described
     * only. Honors the injection switch (an off `injectIntoSystemPrompt` also disables routed
     * injection) but ignores RAG suppression — candidates are needed exactly when the baseline
     * listing was suppressed.
     */
    suspend fun candidatesForSelection(
        userId: String?,
        allowedSkillNames: List<String>
    ): List<Map<String, Any?>> = candidatesForSelectionOwners(listOfNotNull(userId), allowedSkillNames)

    /** Group-aware [candidatesForSelection] over an ordered owner set (self → group → system). */
    suspend fun candidatesForSelectionOwners(
        owners: Collection<String>,
        allowedSkillNames: List<String>
    ): List<Map<String, Any?>> {
        val view = modelView ?: return emptyList()
        firstAccessSync?.invoke(owners)
        if (!fullInjectionActive || allowedSkillNames.isEmpty()) return emptyList()
        val allowed = allowedSkillNames.toSet()
        return view.listEffectiveForOwners(owners)
            .filter { it.skill.name in allowed && !it.skill.description.isNullOrBlank() }
            .map { mapOf("name" to it.skill.name, "description" to it.skill.description) }
    }

    /**
     * Names of the effective model view for this user, independent of the prompt-injection switch
     * and of RAG suppression: the default agent's `load_skill` whitelist derives from here, never
     * from the (possibly suppressed) skillsData rendered into the prompt.
     */
    suspend fun effectiveNames(userId: String?): List<String> = effectiveNamesForOwners(listOfNotNull(userId))

    /** Group-aware [effectiveNames] over an ordered owner set (self → group → system). */
    suspend fun effectiveNamesForOwners(owners: Collection<String>): List<String> {
        firstAccessSync?.invoke(owners)
        return modelView?.listEffectiveForOwners(owners)?.map { it.skill.name } ?: emptyList()
    }
}
