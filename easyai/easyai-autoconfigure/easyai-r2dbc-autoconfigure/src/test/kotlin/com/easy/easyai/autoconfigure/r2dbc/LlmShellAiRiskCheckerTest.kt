package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.permission.PermissionAction
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
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
            listOfNotNull(config)
    }

    private fun checkerWithModel(
        error: Exception? = null,
        responseText: (() -> String)? = null
    ): LlmShellAiRiskChecker {
        val chatModel = mockk<ChatModel>()
        every { chatModel.call(any<Prompt>()) } answers {
            error?.let { throw it }
            ChatResponse(listOf(Generation(AssistantMessage(responseText!!.invoke()))))
        }
        val factory = mockk<ChatModelFactory>()
        every { factory.supports(Protocol.OPENAI) } returns true
        every { factory.create(any(), any()) } returns chatModel
        return LlmShellAiRiskChecker(FakeConfigStore(config), listOf(factory))
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
        fun `escaped quotes in reason are unescaped`() = runBlocking {
            val checker = checkerWithModel { """{"risky": true, "reason": "命令包含 \"rm -rf\""}""" }
            val result = checker.checkRisk("x", null, null, "model-1", rules)

            assertFalse(result.allowed)
            assertEquals("""命令包含 "rm -rf"""", result.reason)
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
