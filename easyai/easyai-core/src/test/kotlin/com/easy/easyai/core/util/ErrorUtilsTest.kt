package com.easy.easyai.core.util

import com.easy.easyai.api.llm.NonTransientAiException
import com.easy.easyai.api.llm.TransientAiException
import com.easy.easyai.core.agent.ContentFilteredException
import com.easy.easyai.core.agent.LlmEndpointUnavailableException
import com.easy.easyai.core.agent.QuotaExhaustedException
import com.easy.easyai.core.agent.ResponseFormatUnsupportedException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [isRetryableError].
 */
class ErrorUtilsTest {

    @Nested
    inner class `classification by exception type` {

        @Test
        fun `endpoint outage wrapper stays retryable without any english marker`() {
            // The agent loop wraps an open breaker in this Chinese message, which matches none of the
            // text checks the function falls back to. The type branch is therefore the only thing
            // keeping the frontend's retry affordance — asserting it directly instead of driving
            // three real model calls until a breaker trips.
            val e = LlmEndpointUnavailableException(
                "模型端点暂时不可用（连续失败已进入熔断保护），约 30s 后可重试",
                null
            )
            assertTrue(isRetryableError(e))
        }

        @Test
        fun `content safety rejection is never retryable`() {
            // Deterministic: replaying the same prompt re-triggers the same filter.
            val e = ContentFilteredException("输出被内容安全策略截断", null)
            assertFalse(isRetryableError(e))
        }

        @Test
        fun `spent quota is never retryable`() {
            // The user-facing message is Chinese and matches none of the text checks, so without the
            // type guard the "default to retryable" fallback would offer a retry that cannot succeed.
            val gateway = TransientAiException("429: Your token-plan 1-month quota has been exhausted.")
            val e = QuotaExhaustedException("模型额度已耗尽，配额将在 10-19 16:00:00 UTC 重置", gateway)
            assertFalse(isRetryableError(e))
        }

        @Test
        fun `refused response format is never retryable`() {
            // What has to change is the model row's declared capability, so a retry button offers an
            // attempt that re-triggers the same refusal.
            val gateway = NonTransientAiException("400: This response_format type is unavailable now")
            val e = ResponseFormatUnsupportedException(
                "模型 deepseek-v4.1-flash 不支持本次请求声明的结构化输出格式", gateway
            )
            assertFalse(isRetryableError(e))
        }
    }
}
