package com.easy.easyai.api.model

/**
 * Applies and validates per-chat overrides of a model config.
 *
 * Overrides never mutate the stored config: [apply] returns a copy used for one run. Validation
 * mirrors the composer's capability gates so an API caller cannot send a combination the UI hides
 * (effort on a model without an effort knob, effort while the protocol's gate is closed). Replay
 * uses [applyRecorded], which drops values the config no longer supports instead of failing.
 */
object ModelOverridePolicy {

    /**
     * Returns a config with the overrides applied. The working window is always clamped to the
     * model's hard maximum — a preference recorded before the config shrank must not make a run
     * unstartable. Effort is applied as-is: request paths call [validate] first, and resume paths
     * use [applyRecorded].
     */
    fun apply(config: ModelProviderConfig, overrides: ModelOverrides?): ModelProviderConfig {
        if (overrides == null || overrides.isEmpty()) return config
        val options = config.options ?: ModelOptions()
        val effort = overrides.effort?.trim()?.lowercase() ?: options.effort
        val contextToken = overrides.contextToken?.let { minOf(it, options.maxContextTokens) } ?: options.contextToken
        return config.copy(options = options.copy(effort = effort, contextToken = contextToken))
    }

    /**
     * Replays overrides recorded on a session against the config as it is now. Values the current
     * profile no longer offers — or whose protocol gate has closed — are dropped instead of failing:
     * a config edit must not make an existing session unresumable. Context is clamped like [apply];
     * a non-positive recorded value is ignored.
     */
    fun applyRecorded(config: ModelProviderConfig, overrides: ModelOverrides?): ModelProviderConfig {
        if (overrides == null || overrides.isEmpty()) return config
        val options = config.options ?: ModelOptions()
        val effort = overrides.effort
            ?.trim()
            ?.lowercase()
            ?.takeIf { effortAllowed(config, options.thinking, it) }
            ?: options.effort
                ?.trim()
                ?.lowercase()
                ?.takeIf { effortAllowed(config, options.thinking, it) }
        val contextToken = overrides.contextToken?.takeIf { it > 0 }
            ?.let { minOf(it, options.maxContextTokens) }
            ?: options.contextToken
        return config.copy(options = options.copy(effort = effort, contextToken = contextToken))
    }

    /** Throws [IllegalArgumentException] with an actionable message when the overrides cannot apply. */
    fun validate(config: ModelProviderConfig, overrides: ModelOverrides) {
        val options = config.options ?: ModelOptions()
        overrides.effort?.let { raw ->
            val effort = raw.trim().lowercase()
            require(effort in ReasoningProfile.EFFORT_LEVELS) {
                "Unknown reasoning effort '$raw'; expected one of ${ReasoningProfile.EFFORT_LEVELS}"
            }
            val profile = config.capabilities?.reasoning
            when (profile?.mode) {
                ReasoningMode.NONE ->
                    throw IllegalArgumentException(
                        "Model ${config.modelId} declares no reasoning support; clear the effort override"
                    )

                // Thinking is fixed on, but the model still only accepts its declared levels.
                ReasoningMode.ALWAYS_ON -> requireOffered(config, profile, effort)

                ReasoningMode.TOGGLEABLE -> {
                    requireOffered(config, profile, effort)
                    val gate = effortGateMessage(config, options.thinking)
                    if (gate != null) throw IllegalArgumentException(gate)
                }

                ReasoningMode.UNKNOWN, null -> Unit
            }
        }
        overrides.contextToken?.let { value ->
            require(value > 0) { "contextToken override must be positive, got: $value" }
        }
    }

    /**
     * Why effort cannot be edited right now, or null when it can. Mirrors the composer's rule:
     * Anthropic's budget and effort are mutually exclusive, so effort applies only while thinking is
     * off; the other protocols only carry effort while thinking is on.
     */
    fun effortGateMessage(config: ModelProviderConfig, thinking: Boolean): String? =
        effortGateMessage(config.protocol, thinking)

    fun effortGateMessage(protocol: ModelProviderInfo.Protocol, thinking: Boolean): String? = when (protocol) {
        ModelProviderInfo.Protocol.ANTHROPIC ->
            if (thinking) {
                "This protocol uses a fixed thinking budget while thinking is on; turn thinking off to set effort"
            } else null

        else -> if (thinking) null else "Effort only applies while thinking is on"
    }

    private fun requireOffered(config: ModelProviderConfig, profile: ReasoningProfile, effort: String) {
        val offered = profile.effortLevels.map { it.trim().lowercase() }
        require(offered.isNotEmpty()) {
            "Model ${config.modelId} declares no effort levels; clear the effort override"
        }
        require(effort in offered) {
            "Model ${config.modelId} accepts effort $offered; got '$effort'"
        }
    }

    /** Whether a recorded effort still expresses something under the current profile and switch. */
    private fun effortAllowed(config: ModelProviderConfig, thinking: Boolean, effort: String): Boolean {
        val profile = config.capabilities?.reasoning ?: return true
        return when (profile.mode) {
            ReasoningMode.NONE -> false
            ReasoningMode.UNKNOWN -> true
            ReasoningMode.ALWAYS_ON -> effort in profile.effortLevels.map { it.trim().lowercase() }
            ReasoningMode.TOGGLEABLE -> effort in profile.effortLevels.map { it.trim().lowercase() } &&
                effortGateMessage(config, thinking) == null
        }
    }
}
