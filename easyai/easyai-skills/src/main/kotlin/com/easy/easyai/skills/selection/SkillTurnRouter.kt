package com.easy.easyai.skills.selection

import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.skills.SkillPromptSource
import org.slf4j.LoggerFactory

/**
 * Routes one fresh user message to the single best-matching skill using the user's
 * `SKILL_SELECTION` aux model (a Bailian System One decision model), so large catalogs can
 * inject one relevant listing per turn instead of the whole one or relying on the main model
 * to call `skill_search` on its own.
 *
 * Every unmet precondition degrades to null — unconfigured aux model, blank query, no
 * candidates, transport failure, `other`, unknown label, or low confidence — and the caller
 * keeps its baseline skill visibility. An unset `SKILL_SELECTION` row means zero HTTP calls
 * and behavior identical to before routing existed.
 */
class SkillTurnRouter(
    private val promptSource: SkillPromptSource,
    private val auxResolver: AuxModelResolver,
    private val client: SkillSelectionClient,
    private val minConfidence: Double,
    private val timeoutMs: Long
) {
    private val logger = LoggerFactory.getLogger(SkillTurnRouter::class.java)

    /** The routed skill as a prompt-visibility entry, or null to keep the baseline. */
    suspend fun route(
        userId: String?,
        allowedSkillNames: List<String>,
        query: String
    ): List<Map<String, Any?>>? = routeOwners(listOfNotNull(userId), allowedSkillNames, query)

    /**
     * Group-aware [route] over an ordered owner set (self → group → system): the selection model and
     * the candidate catalog both resolve through the caller's full visibility set, so a member can be
     * routed to a skill their group owns.
     */
    suspend fun routeOwners(
        owners: Collection<String>,
        allowedSkillNames: List<String>,
        query: String
    ): List<Map<String, Any?>>? {
        if (query.isBlank() || allowedSkillNames.isEmpty()) return null
        val config = auxResolver.resolveConfig(owners, AuxModelTask.SKILL_SELECTION) ?: return null
        val apiKey = config.apiKey?.takeIf { it.isNotBlank() }
        val baseUrl = config.baseUrl?.takeIf { it.isNotBlank() }
        if (apiKey == null || baseUrl == null) {
            logger.warn("Skill selection model '{}' has no apiKey/baseUrl; skipping routing", config.id)
            return null
        }
        val candidates = promptSource.candidatesForSelectionOwners(owners, allowedSkillNames)
        if (candidates.isEmpty()) return null
        if (candidates.size > MAX_SKILL_CRITERIA) {
            logger.warn(
                "Skill catalog for '{}' has {} candidates above the System One choice cap {}; keeping baseline",
                owners, candidates.size, MAX_SKILL_CRITERIA
            )
            return null
        }
        val criteria = LinkedHashMap<String, String>(candidates.size + 1)
        candidates.forEach {
            criteria[it[KEY_NAME] as String] = (it[KEY_DESCRIPTION] as String?)?.take(MAX_DESCRIPTION) ?: ""
        }
        criteria[OTHER_KEY] = "No skill is needed for this request"
        val decision = client.decide(
            SystemOneSelectionRequest(
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelId = config.modelId,
                query = query.trim(),
                instructions = INSTRUCTIONS,
                criteria = criteria,
                timeoutMs = timeoutMs
            )
        ) ?: return null
        val choice = decision.choice?.takeIf { it != OTHER_KEY } ?: return null
        val hit = candidates.firstOrNull { it[KEY_NAME] == choice } ?: return null
        val confidence = decision.confidence ?: 0.0
        if (confidence < minConfidence) {
            logger.info("Skill routing chose '{}' with confidence {} (below {}); keeping baseline", choice, confidence, minConfidence)
            return null
        }
        logger.debug("Skill routing selected '{}' (confidence {})", choice, confidence)
        return listOf(hit)
    }

    companion object {
        /** Choice cap is the System One limit (255) minus the `other` fallback entry. */
        private const val MAX_SKILL_CRITERIA = 254
        private const val MAX_DESCRIPTION = 500
        private const val OTHER_KEY = "other"
        private const val KEY_NAME = "name"
        private const val KEY_DESCRIPTION = "description"
        private const val INSTRUCTIONS =
            "Given the user's request, choose the single skill that best matches it; " +
                "if no skill applies, choose \"other\"."

        /** Stable label the decision API reserves for "none of the above". */
        const val UNSELECTED = OTHER_KEY
    }
}
