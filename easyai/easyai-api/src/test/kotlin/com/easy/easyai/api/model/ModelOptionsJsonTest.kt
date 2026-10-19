package com.easy.easyai.api.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Pins the JSON semantics of [ModelOptions.temperature] across the wire shapes that mean different
 * things: key absent, key explicitly null, an explicit 0.0, and a 0.7 written by an older build.
 *
 * `temperature == null` is what makes the protocol adapters omit the field on the wire
 * (`options.temperature?.let { builder.temperature(it) }`), so a silent collapse of null into 0.0
 * would turn "follow the model's own default" into "force fully deterministic sampling" — two
 * opposite intents nothing downstream can tell apart afterwards.
 *
 * The default is deliberately null rather than 0.7: `@JsonInclude(NON_NULL)` omits a null on write,
 * so a non-null default re-appears on every read of a row that never stored one, which makes the
 * omit path unreachable. Existing rows are unaffected because they persist an explicit
 * `"temperature":0.7` — verified against the live `model_provider_config` table (15 of 15 rows that
 * have an options blob carry it; none has an options blob without the key).
 *
 * The mapper must be built the way model rows are actually read: `R2dbcModelConfigStore` defaults to
 * `SharedObjectMapper.instance`, which is `jacksonObjectMapper()`. A plain
 * `JsonMapper.builder().build()` does NOT register the Kotlin module, and without it every property
 * silently falls back to its Kotlin default regardless of the JSON — so this test would prove
 * nothing with that mapper.
 */
class ModelOptionsJsonTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `absent temperature means the field is not sent`() {
        val options = mapper.readValue("""{"maxTokens":8192}""", ModelOptions::class.java)

        assertNull(options.temperature)
        assertEquals(8192, options.maxTokens)
    }

    @Test
    fun `explicit null temperature means omit rather than zero`() {
        val options = mapper.readValue("""{"temperature":null,"maxTokens":8192}""", ModelOptions::class.java)

        assertNull(
            options.temperature,
            "null collapsed into a numeric value; the omit-on-the-wire path is unusable"
        )
    }

    @Test
    fun `explicit zero temperature survives as zero`() {
        val options = mapper.readValue("""{"temperature":0.0}""", ModelOptions::class.java)

        assertEquals(0.0, options.temperature)
    }

    @Test
    fun `a temperature written by an older build keeps being sent`() {
        val options = mapper.readValue(
            """{"temperature":0.7,"maxTokens":65536,"thinking":true,"effort":"high"}""",
            ModelOptions::class.java
        )

        assertEquals(0.7, options.temperature)
    }

    @Test
    fun `null temperature is omitted on write and still reads back as omit`() {
        val json = mapper.writeValueAsString(ModelOptions(temperature = null, maxTokens = 4096))

        assertTrue(!json.contains("temperature"), "writer must not emit a null temperature: $json")
        assertNull(mapper.readValue(json, ModelOptions::class.java).temperature)
    }
}
