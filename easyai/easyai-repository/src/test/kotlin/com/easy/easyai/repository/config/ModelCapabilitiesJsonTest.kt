package com.easy.easyai.repository.config

import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.StructuredOutputSupport
import com.easy.easyai.common.util.SharedObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for capabilities JSON tolerance: legacy rows without structuredOutput must read
 * as null (undeclared), and values written by a future build (unknown enum constants)
 * must degrade to null instead of failing the whole model_provider_config row.
 */
class ModelCapabilitiesJsonTest {

    private val mapper = capabilitiesMapper(SharedObjectMapper.instance)

    @Test
    fun `legacy row with vision only reads structuredOutput as null`() {
        val caps = mapper.readValue("""{"vision":true}""", ModelCapabilities::class.java)
        assertEquals(true, caps.vision)
        assertNull(caps.structuredOutput)
    }

    @Test
    fun `known enum values round-trip`() {
        for (value in StructuredOutputSupport.entries) {
            val json = SharedObjectMapper.instance.writeValueAsString(
                ModelCapabilities(structuredOutput = value)
            )
            val caps = mapper.readValue(json, ModelCapabilities::class.java)
            assertEquals(value, caps.structuredOutput)
        }
    }

    @Test
    fun `unknown future enum value degrades to null and keeps other fields`() {
        val caps = mapper.readValue(
            """{"vision":true,"structuredOutput":"SOMETHING_NEW"}""",
            ModelCapabilities::class.java
        )
        assertEquals(true, caps.vision)
        assertNull(caps.structuredOutput)
    }

    @Test
    fun `unknown future top-level field is ignored`() {
        val caps = mapper.readValue(
            """{"vision":false,"futureFlag":true}""",
            ModelCapabilities::class.java
        )
        assertEquals(false, caps.vision)
    }
}
