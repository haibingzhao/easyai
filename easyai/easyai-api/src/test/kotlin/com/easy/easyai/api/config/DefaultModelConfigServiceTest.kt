package com.easy.easyai.api.config

import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.api.model.ReasoningMode
import com.easy.easyai.api.model.ReasoningProfile
import com.easy.easyai.api.model.SaveModelProviderConfigRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the reasoning normalization [DefaultModelConfigService] applies on save: NONE and
 * ALWAYS_ON decide the thinking switch themselves, while an explicit effort must be offered by the
 * profile and pass the same protocol gate the adapters enforce — so a row that saves clean also
 * runs clean on the wire.
 */
internal class DefaultModelConfigServiceTest {

    private val store = InMemoryConfigStore()
    private val service = DefaultModelConfigService(store)

    private fun profile(
        mode: ReasoningMode,
        levels: List<String> = emptyList(),
        defaultThinking: Boolean = false,
        defaultEffort: String? = null
    ) = ReasoningProfile(
        mode = mode,
        effortLevels = levels,
        defaultThinking = defaultThinking,
        defaultEffort = defaultEffort,
        source = ReasoningProfile.SOURCE_MANUAL
    )

    private fun request(
        options: ModelOptions = ModelOptions(),
        capabilities: ModelCapabilities? = null,
        protocol: Protocol = Protocol.OPENAI
    ) = SaveModelProviderConfigRequest(
        name = "test",
        protocol = protocol,
        isCustom = true,
        apiKey = "sk-test",
        modelId = "test-model",
        options = options,
        capabilities = capabilities
    )

    private suspend fun save(
        options: ModelOptions = ModelOptions(),
        capabilities: ModelCapabilities? = null,
        protocol: Protocol = Protocol.OPENAI
    ) = service.saveUserConfiguration(request(options, capabilities, protocol), "user-1", listOf("user-1"))

    @Nested
    inner class `mode normalization` {

        @Test
        fun `NONE forces thinking off and drops the effort`() = runBlocking {
            val saved = save(
                ModelOptions(thinking = true, effort = "high"),
                ModelCapabilities(reasoning = profile(ReasoningMode.NONE))
            )

            val options = saved.options!!
            assertEquals(false, options.thinking)
            assertNull(options.effort)
        }

        @Test
        fun `ALWAYS_ON forces thinking on and keeps a declared effort`() = runBlocking {
            val saved = save(
                ModelOptions(thinking = false, effort = "max"),
                ModelCapabilities(
                    reasoning = profile(ReasoningMode.ALWAYS_ON, listOf("high", "max"), defaultThinking = true, defaultEffort = "max")
                )
            )

            val options = saved.options!!
            assertEquals(true, options.thinking)
            assertEquals("max", options.effort)
        }

        @Test
        fun `UNKNOWN passes the row through untouched`() = runBlocking {
            val saved = save(
                ModelOptions(thinking = false, effort = "xhigh"),
                ModelCapabilities(reasoning = profile(ReasoningMode.UNKNOWN))
            )

            assertEquals("xhigh", saved.options!!.effort)
        }
    }

    @Nested
    inner class `effort validation` {

        @Test
        fun `an effort the profile does not offer is rejected`() = runBlocking {
            val failure = assertFailsWith<IllegalArgumentException> {
                save(
                    ModelOptions(thinking = true, effort = "max"),
                    ModelCapabilities(reasoning = profile(ReasoningMode.TOGGLEABLE, listOf("low", "high")))
                )
            }

            assertTrue(failure.message!!.contains("max"), failure.message!!)
        }

        @Test
        fun `TOGGLEABLE with thinking off cannot carry an effort`() = runBlocking {
            // OpenAI-family gate: effort rides on thinking, so a closed switch makes it meaningless.
            val failure = assertFailsWith<IllegalArgumentException> {
                save(
                    ModelOptions(thinking = false, effort = "high"),
                    ModelCapabilities(reasoning = profile(ReasoningMode.TOGGLEABLE, listOf("high")))
                )
            }

            assertTrue(failure.message!!.contains("thinking"), failure.message!!)
        }

        @Test
        fun `the anthropic gate inverts - thinking off is when effort is allowed`(): Unit = runBlocking {
            val open = save(
                ModelOptions(thinking = false, effort = "high"),
                ModelCapabilities(reasoning = profile(ReasoningMode.TOGGLEABLE, listOf("high"))),
                Protocol.ANTHROPIC
            )
            assertEquals("high", open.options!!.effort)

            assertFailsWith<IllegalArgumentException> {
                save(
                    ModelOptions(thinking = true, effort = "high"),
                    ModelCapabilities(reasoning = profile(ReasoningMode.TOGGLEABLE, listOf("high"))),
                    Protocol.ANTHROPIC
                )
            }
        }

        @Test
        fun `an anthropic prefill with thinking on and effort is rejected`(): Unit = runBlocking {
            // The shape the add form used to seed for qwen3.8/glm-5.2/deepseek-v4 behind an
            // Anthropic gateway: the default profile says thinking on and names an effort.
            assertFailsWith<IllegalArgumentException> {
                save(
                    ModelOptions(thinking = true, effort = "xhigh"),
                    ModelCapabilities(
                        reasoning = profile(
                            ReasoningMode.TOGGLEABLE,
                            listOf("low", "medium", "xhigh"),
                            defaultThinking = true,
                            defaultEffort = "xhigh"
                        )
                    ),
                    Protocol.ANTHROPIC
                )
            }
        }

        @Test
        fun `the same anthropic prefill saves once effort is left unset`(): Unit = runBlocking {
            val saved = save(
                ModelOptions(thinking = true),
                ModelCapabilities(
                    reasoning = profile(
                        ReasoningMode.TOGGLEABLE,
                        listOf("low", "medium", "xhigh"),
                        defaultThinking = true,
                        defaultEffort = "xhigh"
                    )
                ),
                Protocol.ANTHROPIC
            )

            assertEquals(true, saved.options!!.thinking)
            assertNull(saved.options!!.effort)
        }
    }

    @Nested
    inner class `profile validation` {

        @Test
        fun `defaultEffort must be one of the declared levels`(): Unit = runBlocking {
            assertFailsWith<IllegalArgumentException> {
                save(
                    capabilities = ModelCapabilities(
                        reasoning = profile(ReasoningMode.TOGGLEABLE, listOf("low"), defaultEffort = "max")
                    )
                )
            }
        }

        @Test
        fun `ALWAYS_ON must default thinking to true`(): Unit = runBlocking {
            assertFailsWith<IllegalArgumentException> {
                save(capabilities = ModelCapabilities(reasoning = profile(ReasoningMode.ALWAYS_ON, listOf("max"))))
            }
        }

        @Test
        fun `NONE cannot declare effort levels`(): Unit = runBlocking {
            assertFailsWith<IllegalArgumentException> {
                save(capabilities = ModelCapabilities(reasoning = profile(ReasoningMode.NONE, listOf("high"))))
            }
        }
    }

    @Nested
    inner class `profile canonicalization` {

        @Test
        fun `mixed-case levels are stored lowercase and in canonical order`(): Unit = runBlocking {
            val saved = save(
                ModelOptions(thinking = true, effort = "high"),
                ModelCapabilities(
                    reasoning = profile(
                        ReasoningMode.TOGGLEABLE,
                        listOf("HIGH", "low", "HIGH"),
                        defaultThinking = true
                    )
                )
            )

            assertEquals(listOf("low", "high"), saved.capabilities?.reasoning?.effortLevels)
            assertEquals("high", saved.options?.effort)
        }

        @Test
        fun `defaultEffort is matched case-insensitively and stored lowercase`(): Unit = runBlocking {
            val saved = save(
                capabilities = ModelCapabilities(
                    reasoning = profile(
                        ReasoningMode.TOGGLEABLE,
                        listOf("LOW", "MAX"),
                        defaultThinking = true,
                        defaultEffort = "MAX"
                    )
                )
            )

            assertEquals(listOf("low", "max"), saved.capabilities?.reasoning?.effortLevels)
            assertEquals("max", saved.capabilities?.reasoning?.defaultEffort)
        }

        @Test
        fun `an unknown level is still rejected`(): Unit = runBlocking {
            assertFailsWith<IllegalArgumentException> {
                save(
                    capabilities = ModelCapabilities(
                        reasoning = profile(ReasoningMode.TOGGLEABLE, listOf("LOW", "ultra"), defaultThinking = true)
                    )
                )
            }
        }
    }

    private class InMemoryConfigStore : ModelProviderConfigStore {
        private val rows = mutableMapOf<String, ModelProviderConfig>()

        override suspend fun getConfig(id: String, userId: String): ModelProviderConfig? = rows[id]

        override suspend fun saveConfig(config: ModelProviderConfig, userId: String) {
            rows[config.id] = config
        }

        override suspend fun deleteConfig(id: String, userId: String): Boolean = rows.remove(id) != null

        override suspend fun getModelConfigs(modelType: ModelType, userId: String): List<ModelProviderConfig> =
            rows.values.filter { it.modelType == modelType }
    }
}
