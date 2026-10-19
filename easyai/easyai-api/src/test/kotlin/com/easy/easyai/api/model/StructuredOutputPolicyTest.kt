package com.easy.easyai.api.model

import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Covers [StructuredOutputPolicy]: a row that declares nothing must not be treated as a row that was
 * tested and passed, while a declared value is handed to the adapter untouched.
 */
class StructuredOutputPolicyTest {

    private fun configWith(capabilities: ModelCapabilities?) = ModelProviderConfig(
        id = "cfg-1",
        name = "test",
        protocol = Protocol.OPENAI,
        isCustom = true,
        modelId = "deepseek-v4.1-flash",
        capabilities = capabilities
    )

    @Test
    fun `no capabilities block at all reads as none`() {
        assertEquals(StructuredOutputSupport.NONE, StructuredOutputPolicy.of(configWith(null)))
    }

    @Test
    fun `a legacy row that declares only vision reads as none`() {
        val caps = ModelCapabilities(vision = true)

        assertEquals(StructuredOutputSupport.NONE, StructuredOutputPolicy.of(configWith(caps)))
    }

    @Test
    fun `a declared level is handed through`() {
        for (support in StructuredOutputSupport.entries) {
            val config = configWith(ModelCapabilities(structuredOutput = support))

            assertEquals(support, StructuredOutputPolicy.of(config))
        }
    }
}
