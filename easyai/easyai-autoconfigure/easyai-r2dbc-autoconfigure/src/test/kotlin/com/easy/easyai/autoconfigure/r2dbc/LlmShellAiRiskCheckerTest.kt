package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.permission.PermissionAction
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.easy.easyai.api.llm.AssistantMessage
import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.ChatResponse
import com.easy.easyai.api.llm.Generation
import com.easy.easyai.api.llm.Prompt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [LlmShellAiRiskChecker]: JSON verdict parsing, prose tolerance,
 * and degradation to a non-allowed result for missing config / unknown
 * protocol / unparsable output. LLM transport errors propagate and are
 * degraded to ASK by PermissionService.
 */
class LlmShellAiRiskCheckerTest {

    private val config = ModelProviderConfig(
        id = "model-1",
        name = "Test Model",
        protocol = Protocol.OPENAI,
        isCustom = false,
        modelId = "gpt-test"
    )

    private class FakeConfigStore(private val config: ModelProviderConfig?) : ModelProviderConfigStore {
        override suspend fun getConfig(id: String, userId: String): ModelProviderConfig? = config
        override suspend fun saveConfig(config: ModelProviderConfig, userId: String) {}
        override suspend fun deleteConfig(id: String, userId: String): Boolean = false
        override suspend fun getAllConfigs(userId: String): List<ModelProviderConfig> =
            getModelConfigs(ModelType.CHAT, userId)

        override suspend fun getModelConfigs(modelType: ModelType, userId: String): List<ModelProviderConfig> =
            listOfNotNull(config).filter { it.modelType == modelType }
    }

    /** Exposes the config only to its owner (or system), like the real store's user-scoped lookup. */
    private class OwnerScopedConfigStore(
        private val owner: String,
        private val config: ModelProviderConfig?
    ) : ModelProviderConfigStore {
        override suspend fun getConfig(id: String, userId: String): ModelProviderConfig? =
            config.takeIf { userId == owner || userId == "system" }

        override suspend fun saveConfig(config: ModelProviderConfig, userId: String) {}
        override suspend fun deleteConfig(id: String, userId: String): Boolean = false
        override suspend fun getAllConfigs(userId: String): List<ModelProviderConfig> =
            getModelConfigs(ModelType.CHAT, userId)

        override suspend fun getModelConfigs(modelType: ModelType, userId: String): List<ModelProviderConfig> =
            listOfNotNull(config.takeIf { userId == owner || userId == "system" })
                .filter { it.modelType == modelType }
    }

    private fun checkerWithModel(
        error: Exception? = null,
        responseText: (() -> String)? = null
    ): LlmShellAiRiskChecker {
        val chatModel = mockk<ChatModel>()
        coEvery { chatModel.callAsync(any<Prompt>()) } answers {
            error?.let { throw it }
            ChatResponse(listOf(Generation(AssistantMessage(responseText!!.invoke()))))
        }
        val factory = mockk<ChatModelFactory>()
        every { factory.supports(Protocol.OPENAI) } returns true
        every { factory.create(any(), any()) } returns chatModel
        return LlmShellAiRiskChecker(FakeConfigStore(config), listOf(factory))
    }

    private fun checkerWithStore(
        store: ModelProviderConfigStore,
        responseText: () -> String
    ): LlmShellAiRiskChecker {
        val chatModel = mockk<ChatModel>()
        coEvery { chatModel.callAsync(any<Prompt>()) } answers {
            ChatResponse(listOf(Generation(AssistantMessage(responseText()))))
        }
        val factory = mockk<ChatModelFactory>()
        every { factory.supports(Protocol.OPENAI) } returns true
        every { factory.create(any(), any()) } returns chatModel
        return LlmShellAiRiskChecker(store, listOf(factory))
    }

    private val rules = listOf(PermissionRule("shell.ai", "model-1", PermissionAction.ALLOW))

    @Nested
    inner class `verdict parsing` {

        @Test
        fun `safe verdict returns allowed with reason`() = runBlocking {
            val checker = checkerWithModel { """{"risky": false, "reason": "仅读取项目内文件"}""" }
            val result = checker.checkRisk("cat a.txt", null, "user-1", "model-1", rules)

            assertTrue(result.allowed)
            assertEquals("仅读取项目内文件", result.reason)
        }

        @Test
        fun `risky verdict returns not allowed with reason`() = runBlocking {
            val checker = checkerWithModel { """{"risky": true, "reason": "将删除项目外文件"}""" }
            val result = checker.checkRisk("rm -rf /tmp/x", null, null, "model-1", rules)

            assertFalse(result.allowed)
            assertEquals("将删除项目外文件", result.reason)
        }

        @Test
        fun `prose around the JSON payload is tolerated`() = runBlocking {
            val checker = checkerWithModel {
                "Here is my assessment:\n```json\n{\"risky\": false, \"reason\": \"safe\"}\n```\nDone."
            }
            val result = checker.checkRisk("ls", null, null, "model-1", rules)

            assertTrue(result.allowed)
            assertEquals("safe", result.reason)
        }

        @Test
        fun `fence language marker is not mistaken for the payload`() = runBlocking {
            val checker = checkerWithModel {
                "```JSON\n{\"risky\": false, \"reason\": \"safe\"}\n```"
            }
            val result = checker.checkRisk("ls", null, null, "model-1", rules)

            assertTrue(result.allowed)
            assertEquals("safe", result.reason)
        }

        @Test
        fun `escaped quotes in reason are unescaped`() = runBlocking {
            val checker = checkerWithModel { """{"risky": true, "reason": "命令包含 \"rm -rf\""}""" }
            val result = checker.checkRisk("x", null, null, "model-1", rules)

            assertFalse(result.allowed)
            assertEquals("""命令包含 "rm -rf"""", result.reason)
        }
    }

    @Nested
    inner class `group-shared model config` {

        @Test
        fun `a member resolves the owner's config through the chat owners`() = runBlocking {
            val store = OwnerScopedConfigStore("grp_1", config)
            val checker = checkerWithStore(store) { """{"risky": false, "reason": "safe"}""" }

            val result = checker.checkRisk(
                "ls", null, "bob", "model-1", rules, setOf("bob", "grp_1", "system")
            )

            assertTrue(result.allowed)
            assertEquals("safe", result.reason)
        }

        @Test
        fun `legacy user-only lookup still misses the owner's config`() = runBlocking {
            val store = OwnerScopedConfigStore("grp_1", config)
            val checker = checkerWithStore(store) { """{"risky": false}""" }

            val result = checker.checkRisk("ls", null, "bob", "model-1", rules)

            assertFalse(result.allowed)
            assertEquals("AI 检查模型配置不存在", result.reason)
        }
    }

    @Nested
    inner class `degradation paths` {

        @Test
        fun `missing model config returns not allowed`() = runBlocking {
            val checker = LlmShellAiRiskChecker(FakeConfigStore(null), emptyList())
            val result = checker.checkRisk("ls", null, null, "model-1", rules)

            assertFalse(result.allowed)
            assertEquals("AI 检查模型配置不存在", result.reason)
        }

        @Test
        fun `unsupported protocol returns not allowed`() = runBlocking {
            val anthropicConfig = config.copy(protocol = Protocol.ANTHROPIC)
            val factory = mockk<ChatModelFactory>()
            every { factory.supports(Protocol.ANTHROPIC) } returns false
            val checker = LlmShellAiRiskChecker(FakeConfigStore(anthropicConfig), listOf(factory))
            val result = checker.checkRisk("ls", null, null, "model-1", rules)

            assertFalse(result.allowed)
            assertEquals("AI 检查模型协议不受支持", result.reason)
        }

        @Test
        fun `output without JSON payload degrades to not allowed`() = runBlocking {
            val checker = checkerWithModel { "I cannot assess this command." }
            val result = checker.checkRisk("ls", null, null, "model-1", rules)

            assertFalse(result.allowed)
            assertEquals("AI 检查输出无法解析", result.reason)
        }

        @Test
        fun `JSON missing the risky field degrades to not allowed`() = runBlocking {
            val checker = checkerWithModel { """{"risk": false}""" }
            val result = checker.checkRisk("ls", null, null, "model-1", rules)

            assertFalse(result.allowed)
            assertEquals("AI 检查输出无法解析", result.reason)
        }

        @Test
        fun `verdict without reason yields null reason`() = runBlocking {
            val checker = checkerWithModel { """{"risky": false}""" }
            val result = checker.checkRisk("ls", null, null, "model-1", rules)

            assertTrue(result.allowed)
            assertNull(result.reason)
        }

        @Test
        fun `LLM transport error propagates to the caller`() {
            val checker = checkerWithModel(error = RuntimeException("connection refused"))

            assertFailsWith<RuntimeException> {
                runBlocking { checker.checkRisk("ls", null, null, "model-1", rules) }
            }
        }
    }
}
