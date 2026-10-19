package com.easy.easyai.api.model

import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Covers [SamplingPolicy]: which number each [SamplingIntent] produces, and the two cases where the
 * policy must stay out of the way — a row a human tuned, and a row with no options block at all.
 */
class SamplingPolicyTest {

    private fun configWith(temperature: Double?, maxTokens: Int = 65_536) = ModelProviderConfig(
        id = "cfg-1",
        name = "test",
        protocol = Protocol.OPENAI,
        isCustom = true,
        modelId = "qwen3.8-max",
        options = temperature?.let { ModelOptions(temperature = it, maxTokens = maxTokens) }
    )

    @Test
    fun `follow config sends the row value untouched`() {
        val config = configWith(0.7)

        val decision = SamplingPolicy.decide(config, SamplingIntent.FOLLOW_CONFIG)

        assertEquals(0.7, decision.temperature)
        assertEquals(TemperatureSource.ROW, decision.source)
        assertSame(config, SamplingPolicy.applyTo(config, SamplingIntent.FOLLOW_CONFIG))
    }

    @Test
    fun `deterministic pins the low value over a conversational row`() {
        val config = configWith(0.7)

        val resolved = SamplingPolicy.applyTo(config, SamplingIntent.DETERMINISTIC)

        assertEquals(0.1, resolved.options?.temperature)
        assertEquals(TemperatureSource.PINNED, SamplingPolicy.decide(config, SamplingIntent.DETERMINISTIC).source)
        // Only sampling moves; the human's token budget and reasoning flags survive.
        assertEquals(config.options?.maxTokens, resolved.options?.maxTokens)
        assertEquals(config.options?.thinking, resolved.options?.thinking)
    }

    @Test
    fun `follow model default sends nothing`() {
        val decision = SamplingPolicy.decide(configWith(0.7), SamplingIntent.FOLLOW_MODEL_DEFAULT)

        assertNull(decision.temperature)
        assertEquals(TemperatureSource.UNSET_BY_INTENT, decision.source)
    }

    @Test
    fun `deliberative leaves sampling to the row`() {
        val decision = SamplingPolicy.decide(configWith(0.9), SamplingIntent.DELIBERATIVE)

        assertEquals(0.9, decision.temperature)
        assertEquals(TemperatureSource.ROW, decision.source)
    }

    @Test
    fun `a row without an options block is never given one`() {
        val config = configWith(null)

        val decision = SamplingPolicy.decide(config, SamplingIntent.DETERMINISTIC)
        val resolved = SamplingPolicy.applyTo(config, SamplingIntent.DETERMINISTIC)

        assertNull(decision.temperature)
        assertEquals(TemperatureSource.UNSET_WITHOUT_OPTIONS_ROW, decision.source)
        // Inventing an options block would also invent maxTokens, doubling Anthropic's 8192 default.
        assertSame(config, resolved)
        assertNull(resolved.options)
    }

    @Test
    fun `applying an intent does not mutate the shared row`() {
        val config = configWith(0.7)

        SamplingPolicy.applyTo(config, SamplingIntent.DETERMINISTIC)

        assertEquals(0.7, config.options?.temperature)
    }

    @Test
    fun `a model that rejects sampling never receives a pinned value`() {
        val rejecting = configWith(0.7).copy(capabilities = ModelCapabilities(sampling = SamplingSupport.NOT_SETTABLE))

        val resolved = SamplingPolicy.applyTo(rejecting, SamplingIntent.DETERMINISTIC)

        assertNull(resolved.options?.temperature)
        assertEquals(
            TemperatureSource.UNSET_BY_MODEL_CAPABILITY,
            SamplingPolicy.decide(rejecting, SamplingIntent.DETERMINISTIC).source
        )
    }

    @Test
    fun `an undeclared sampling capability still sends the row value`() {
        val legacy = configWith(0.7).copy(capabilities = ModelCapabilities(sampling = null))

        assertEquals(0.7, SamplingPolicy.decide(legacy, SamplingIntent.FOLLOW_CONFIG).temperature)
    }

    @Test
    fun `declared settable sampling keeps the row value`() {
        val declared = configWith(0.3).copy(capabilities = ModelCapabilities(sampling = SamplingSupport.SETTABLE))

        assertEquals(0.3, SamplingPolicy.decide(declared, SamplingIntent.FOLLOW_CONFIG).temperature)
    }

    @Test
    fun `rowTemperature is what the protocol factories may put on the wire`() {
        // The gate lives here rather than in each factory, so a row nobody routed through the agent
        // loop (aux models, dictation, a direct factory.create) cannot smuggle sampling to a model
        // that rejects it.
        val rejecting = configWith(0.7).copy(capabilities = ModelCapabilities(sampling = SamplingSupport.NOT_SETTABLE))

        assertEquals(0.7, SamplingPolicy.rowTemperature(configWith(0.7)))
        assertNull(SamplingPolicy.rowTemperature(rejecting))
        assertNull(SamplingPolicy.rowTemperature(configWith(null)))
    }
}
