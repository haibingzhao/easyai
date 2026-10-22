package com.easy.easyai.api.model

/**
 * Resolves the thinking/effort matrix for a model id from vendor documentation.
 *
 * The catalog only carries a boolean "reasoning" flag, so this table supplies what the flag cannot:
 * whether thinking may be turned off and which effort levels the model accepts. Rules match by
 * family prefix (date/suffix variants included) and are the single place to update when a vendor
 * changes its matrix. A profile already stored on a row wins; this only fills the gaps.
 *
 * Lives in the API module so both the web catalog and the repository's config row mapper can
 * resolve the same matrix without a web-layer dependency.
 */
object ReasoningProfiles {

    /** Every effort name the adapters understand, in ascending order. */
    private val EFFORT_LEVELS: List<String> = ReasoningProfile.EFFORT_LEVELS

    @JvmStatic
    fun resolve(modelId: String, catalogThinking: Boolean? = null): ReasoningProfile {
        val id = normalize(modelId)
        ruleFor(id)?.let { return it }
        return if (catalogThinking == false) {
            ReasoningProfile(mode = ReasoningMode.NONE, source = ReasoningProfile.SOURCE_CATALOG)
        } else {
            ReasoningProfile.UNKNOWN
        }
    }

    /** Dedupe and order effort names by the canonical low→max sequence. */
    @JvmStatic
    fun canonicalLevels(levels: Collection<String>): List<String> {
        val lowered = levels.map { it.trim().lowercase() }.toSet()
        return EFFORT_LEVELS.filter { it in lowered }
    }

    private fun normalize(modelId: String): String =
        modelId.trim().lowercase().substringAfterLast('/')

    private fun ruleFor(id: String): ReasoningProfile? = when {
        id.startsWith("glm-5.3") -> profile(
            ReasoningMode.ALWAYS_ON, listOf("low", "high", "max"), defaultThinking = true, defaultEffort = "max"
        )

        id.startsWith("glm-5.2") || id.startsWith("glm-5.1") || id == "glm-5" || id.startsWith("glm-5-") ->
            profile(ReasoningMode.TOGGLEABLE, listOf("high", "max"), defaultThinking = true, defaultEffort = "high")

        id.startsWith("qwen3.8") ->
            profile(
                ReasoningMode.TOGGLEABLE, listOf("low", "medium", "xhigh"),
                defaultThinking = true, defaultEffort = "xhigh"
            )

        id.startsWith("qwen3.7") ->
            profile(ReasoningMode.TOGGLEABLE, emptyList(), defaultThinking = true)

        id.startsWith("deepseek-v4-flash-0731") || id.startsWith("deepseek-v4-pro-0813") ->
            profile(ReasoningMode.TOGGLEABLE, listOf("low", "high", "max"), defaultThinking = true, defaultEffort = "high")

        id.startsWith("deepseek-v4") ->
            profile(ReasoningMode.TOGGLEABLE, listOf("high", "max"), defaultThinking = true, defaultEffort = "high")

        id.startsWith("qwen3-vl") ->
            profile(ReasoningMode.TOGGLEABLE, emptyList(), defaultThinking = false)

        else -> null
    }

    private fun profile(
        mode: ReasoningMode,
        effortLevels: List<String>,
        defaultThinking: Boolean,
        defaultEffort: String? = null
    ) = ReasoningProfile(
        mode = mode,
        effortLevels = canonicalLevels(effortLevels),
        defaultThinking = defaultThinking,
        defaultEffort = defaultEffort,
        source = ReasoningProfile.SOURCE_VENDOR_DOC
    )
}
