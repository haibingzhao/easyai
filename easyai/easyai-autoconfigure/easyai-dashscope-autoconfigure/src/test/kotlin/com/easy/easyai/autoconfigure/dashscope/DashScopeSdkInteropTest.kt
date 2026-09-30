package com.easy.easyai.autoconfigure.dashscope

import com.alibaba.dashscope.aigc.generation.GenerationResult
import com.alibaba.dashscope.tools.ToolCallFunction
import com.alibaba.dashscope.utils.JsonUtils
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins the DashScope SDK object-model facts the adapter is built on.
 *
 * `ToolCallFunction.CallFunction`, `GenerationOutput.Choice` and the token-detail holders are
 * non-static Java inner classes: Kotlin cannot call a non-static inner constructor (KT-7857), and
 * the SDK's request/response path only works because its own Gson materializes them. These tests
 * fail loudly if a future SDK release breaks either assumption.
 */
internal class DashScopeSdkInteropTest {

    private val streamingToolCallFrame = """
        {
          "status_code": 200,
          "request_id": "req-1",
          "code": "",
          "message": "",
          "output": {
            "choices": [
              {
                "index": 0,
                "finish_reason": "",
                "message": {
                  "role": "assistant",
                  "content": "",
                  "reasoning_content": "先查",
                  "tool_calls": [
                    {
                      "index": 0,
                      "id": "call_1",
                      "type": "function",
                      "function": {"name": "get_weather", "arguments": "{\"ci"}
                    }
                  ]
                }
              }
            ]
          },
          "usage": {"input_tokens": 0, "output_tokens": 0, "total_tokens": 0}
        }
    """.trimIndent()

    @Nested
    inner class `response deserialization` {

        @Test
        fun `materializes nested tool call fragments`() {
            val result = JsonUtils.fromJson(streamingToolCallFrame, GenerationResult::class.java)

            val message = result.output.choices.first().message
            assertEquals("先查", message.reasoningContent)
            val call = message.toolCalls.first() as ToolCallFunction
            assertEquals("call_1", call.id)
            val function = call.function
            assertNotNull(function)
            assertEquals("get_weather", function.name)
            assertEquals("{\"ci", function.arguments)
        }

        @Test
        fun `materializes cached token details`() {
            val json = """
                {
                  "status_code": 200,
                  "output": {"text": "ok", "finish_reason": "stop"},
                  "usage": {
                    "input_tokens": 120,
                    "output_tokens": 30,
                    "total_tokens": 150,
                    "output_tokens_details": {"reasoning_tokens": 12},
                    "prompt_tokens_details": {
                      "cache_type": "ephemeral",
                      "cached_tokens": 96,
                      "cache_creation_input_tokens": 24
                    }
                  }
                }
            """.trimIndent()

            val usage = JsonUtils.fromJson(json, GenerationResult::class.java).usage

            assertEquals(120, usage.inputTokens)
            assertEquals(30, usage.outputTokens)
            assertEquals(12, usage.outputTokensDetails.reasoningTokens)
            assertEquals(96, usage.promptTokensDetails.cachedTokens)
            assertEquals(24, usage.promptTokensDetails.cacheCreationInputTokens)
            assertEquals("ephemeral", usage.promptTokensDetails.cacheType)
        }
    }
}
