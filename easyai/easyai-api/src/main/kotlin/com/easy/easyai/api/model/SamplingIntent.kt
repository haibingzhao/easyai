package com.easy.easyai.api.model

/**
 * Why an LLM call is being made, from the framework's point of view.
 *
 * Sampling parameters are per-connection today: one [ModelProviderConfig] row backs the chat loop,
 * context compaction, dictation refinement, and the judgement calls that gate a trading decision,
 * so a row tuned for conversational variety silently decides how reproducible a risk verdict is.
 * A call site declares its [SamplingIntent] instead, and [SamplingPolicy] turns intent + row +
 * model capability into the options that actually go on the wire.
 *
 * Intents describe *variance tolerance*, not which model runs: the same model can serve several.
 */
enum class SamplingIntent {
    /** Send whatever the row says. The chat loop's intent — a human picked those numbers. */
    FOLLOW_CONFIG,

    /**
     * Reproducible judgement or extraction: routing, risk verdicts, summarisation, structured
     * output repair. Sampling is pinned low so two runs over the same market data agree.
     */
    DETERMINISTIC,

    /**
     * Multi-agent debate turns, where agreeing with the previous speaker is the failure mode.
     * No sampling override yet — declaring the intent still records in the trace why this call is
     * allowed to differ from its neighbours.
     */
    DELIBERATIVE,

    /**
     * Send no sampling parameters at all and let the vendor's per-model default apply. Correct for
     * models tuned by their vendor (several Qwen math/vision models default to 0 or 0.01), wrong for
     * anyone who needs a reproducible result, because the effective value is not observable.
     *
     * This needs the request options to be built from the rewritten row, which is what the agent loop
     * does ([SamplingPolicy.applyTo] and then the protocol factory's `build`). A caller that instead
     * hands an adapter a portable [com.easy.easyai.api.llm.ChatOptions] with a null temperature gets
     * that adapter's own default back: OPENAI and ANTHROPIC bake only the model name into their cached
     * defaults, so the field stays absent, while DASHSCOPE bakes the row's number — so "omit" is not
     * achievable there. Route the intent through `applyTo` + `build` when the absence has to hold.
     */
    FOLLOW_MODEL_DEFAULT
}
