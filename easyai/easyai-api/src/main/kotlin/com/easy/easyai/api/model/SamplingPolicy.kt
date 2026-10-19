package com.easy.easyai.api.model

/**
 * Where the sampling temperature a call ends up using came from, in so far as easyai decided it.
 *
 * Recorded next to the value that actually went on the wire so a later reading of a run can tell
 * "the row said nothing" apart from "the framework pinned it" — both look identical once the number
 * is gone. Whether the gateway then applied its own default or silently clamped it stays outside
 * easyai's observation, which is the point of naming the source instead of logging a bare number.
 */
enum class TemperatureSource {
    /** Nothing pinned: use the row's own value (or the row's absence of one). */
    ROW,

    /** The framework pinned a low value because this call is a judgement or extraction call. */
    PINNED,

    /** The caller explicitly asked for no sampling parameters, so the vendor default applies. */
    UNSET_BY_INTENT,

    /**
     * The row's model declares [ModelCapabilities.sampling] = NOT_SETTABLE, so no intent may send a
     * number. This is the flag that keeps a current-generation reasoning model from returning 400 on
     * a request easyai built out of its own goodness.
     */
    UNSET_BY_MODEL_CAPABILITY,

    /**
     * The row has no options block at all. Pinning here would mean inventing one, which also
     * invents `maxTokens` and silently raises an Anthropic call's output cap from the adapter's
     * 8192 default to [ModelOptions.maxTokens]'s 16384 — so the request is left untouched.
     */
    UNSET_WITHOUT_OPTIONS_ROW
}

/** [SamplingPolicy]'s answer: what to send, and why. */
data class SamplingDecision(
    val temperature: Double?,
    val source: TemperatureSource
)

/**
 * Turns a [SamplingIntent] plus a model row into the sampling parameters one call should use.
 *
 * Two things decide the number, and neither is a protocol: the intent (how much variance this call
 * may have) and the model's declared [ModelCapabilities.sampling] support. Whether a request may
 * still carry the number in a given *mode* — a reasoning mode that ignores or rejects sampling — is
 * protocol-specific and stays with the adapter that speaks that protocol.
 */
object SamplingPolicy {

    /**
     * Judgement and extraction calls pin to this rather than to 0.0: a fully greedy decode is known
     * to loop on repeated phrasing, and 0.1 is already outside the band where wording drifts
     * materially while keeping that failure mode available to fall back on.
     */
    private const val DETERMINISTIC_TEMPERATURE = 0.1

    /**
     * The sampling decision for [config] under [intent]. Callers that only build options pass
     * [SamplingIntent.FOLLOW_CONFIG] and get the row's own value back.
     */
    @JvmStatic
    fun decide(config: ModelProviderConfig, intent: SamplingIntent): SamplingDecision {
        val row = config.options
            ?: return SamplingDecision(temperature = null, source = TemperatureSource.UNSET_WITHOUT_OPTIONS_ROW)
        if (config.capabilities?.sampling == SamplingSupport.NOT_SETTABLE) {
            return SamplingDecision(temperature = null, source = TemperatureSource.UNSET_BY_MODEL_CAPABILITY)
        }
        return when (intent) {
            SamplingIntent.FOLLOW_MODEL_DEFAULT ->
                SamplingDecision(temperature = null, source = TemperatureSource.UNSET_BY_INTENT)

            SamplingIntent.DETERMINISTIC ->
                SamplingDecision(
                    temperature = DETERMINISTIC_TEMPERATURE,
                    source = TemperatureSource.PINNED
                )

            SamplingIntent.FOLLOW_CONFIG, SamplingIntent.DELIBERATIVE ->
                SamplingDecision(temperature = row.temperature, source = TemperatureSource.ROW)
        }
    }

    /**
     * The row's own temperature as far as this model may receive it: null when the row declares no
     * options, or when the model rejects sampling.
     *
     * Protocol factories read the row through this and not through `config.options.temperature`,
     * because a portable ChatOptions passed per call can only say "inherit the defaults" — it cannot
     * say "omit". A model that answers 400 to sampling therefore has to be stopped where the
     * defaults are built, which every call path shares.
     */
    @JvmStatic
    fun rowTemperature(config: ModelProviderConfig): Double? =
        decide(config, SamplingIntent.FOLLOW_CONFIG).temperature

    /**
     * [config] rewritten so the protocol factory picks up [intent]'s sampling.
     *
     * Returns the same instance when the row already matches the decision, so a chat row tuned by a
     * human is passed through byte-for-byte and caches keyed on the config keep hitting.
     */
    @JvmStatic
    fun applyTo(config: ModelProviderConfig, intent: SamplingIntent): ModelProviderConfig =
        apply(config, decide(config, intent))

    /** As [applyTo], but for a decision already taken — so a caller can record it without computing twice. */
    @JvmStatic
    fun apply(config: ModelProviderConfig, decision: SamplingDecision): ModelProviderConfig {
        val row = config.options ?: return config
        if (row.temperature == decision.temperature) return config
        return config.copy(options = row.copy(temperature = decision.temperature))
    }
}

/**
 * What sampling a call actually used, recorded on the assistant message that came back with it.
 *
 * [requestedTemperature] is what easyai decided; [sentTemperature] is the value easyai handed the
 * adapter for this call. The loop always builds those options from the policy-rewritten row, so the
 * two differ only where the adapter's own rules removed it — a reasoning mode that must not carry
 * sampling — and that difference is the point: a run replayed from these fields can tell "easyai
 * asked for nothing" from "easyai asked for 0.1 and the adapter dropped it".
 *
 * Neither proves what the gateway finally applied — a model that clamps a value below its own floor
 * is invisible from here. That gap is why [source] is recorded instead of a bare number.
 */
data class SamplingTrace(
    val intent: SamplingIntent,
    val source: TemperatureSource,
    val requestedTemperature: Double?,
    val sentTemperature: Double?
)
