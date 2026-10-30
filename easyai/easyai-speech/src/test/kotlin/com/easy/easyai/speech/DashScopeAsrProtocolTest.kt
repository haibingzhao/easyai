package com.easy.easyai.speech

import com.easy.easyai.common.util.SharedObjectMapper
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frame encoding and event decoding for the recognition protocol. Positional shapes are asserted
 * here so a transport test never has to care what the JSON looks like.
 */
class DashScopeAsrProtocolTest {

    private val root = SharedObjectMapper.instance

    @Nested
    inner class RunTask {

        @Test
        fun `carries the recognition envelope and the fixed audio parameters`() {
            val frame = root.readTree(
                DashScopeAsrProtocol.runTask("task-1", "qwen-audio-3.1-asr-flash-streaming", emptyMap())
            )
            val payload = frame.path("payload")
            assertEquals("run-task", frame.path("header").path("action").asString())
            assertEquals("task-1", frame.path("header").path("task_id").asString())
            assertEquals("duplex", frame.path("header").path("streaming").asString())
            assertEquals("audio", payload.path("task_group").asString())
            assertEquals("asr", payload.path("task").asString())
            assertEquals("recognition", payload.path("function").asString())
            assertEquals("qwen-audio-3.1-asr-flash-streaming", payload.path("model").asString())
            val parameters = payload.path("parameters")
            assertEquals("pcm", parameters.path("format").asString())
            assertEquals(16000, parameters.path("sample_rate").asInt())
            assertTrue(parameters.path("heartbeat").asBoolean())
            assertTrue(payload.path("input").isObject)
            assertEquals(0, payload.path("input").size())
        }

        @Test
        fun `forwards the validated model parameters`() {
            val frame = root.readTree(
                DashScopeAsrProtocol.runTask(
                    "task-1",
                    "qwen-audio-3.1-asr-flash-streaming",
                    mapOf("vad_model" to "near_meeting_16k", "max_sentence_silence" to 400)
                )
            )
            val parameters = frame.path("payload").path("parameters")
            assertEquals("near_meeting_16k", parameters.path("vad_model").asString())
            assertEquals(400, parameters.path("max_sentence_silence").asInt())
        }

        @Test
        fun `a caller cannot make the request disagree with the bytes being sent`() {
            val frame = root.readTree(
                DashScopeAsrProtocol.runTask(
                    "task-1",
                    "qwen-audio-3.1-asr-flash-message",
                    mapOf("format" to "mp3", "sample_rate" to 44100, "heartbeat" to false)
                )
            )
            val parameters = frame.path("payload").path("parameters")
            assertEquals("pcm", parameters.path("format").asString())
            assertEquals(16000, parameters.path("sample_rate").asInt())
            assertTrue(parameters.path("heartbeat").asBoolean())
        }

        @Test
        fun `rejects a blank task id or model`() {
            assertFailsWith<IllegalArgumentException> { DashScopeAsrProtocol.runTask("", "m", emptyMap()) }
            assertFailsWith<IllegalArgumentException> { DashScopeAsrProtocol.runTask("t", "  ", emptyMap()) }
        }
    }

    @Nested
    inner class FinishTask {

        @Test
        fun `closes the same task with an empty input and no cancel directive`() {
            val frame = root.readTree(DashScopeAsrProtocol.finishTask("task-1"))
            assertEquals("finish-task", frame.path("header").path("action").asString())
            assertEquals("task-1", frame.path("header").path("task_id").asString())
            assertEquals(0, frame.path("payload").path("input").size())
            assertNull(frame.path("header").path("directive").asString(null))
        }
    }

    @Nested
    inner class Parse {

        @Test
        fun `reads task-started`() {
            val event = DashScopeAsrProtocol.parse(
                """{"header":{"event":"task-started","task_id":"task-1"},"payload":{}}"""
            )
            assertEquals(AsrEventKind.STARTED, event?.kind)
            assertEquals("task-1", event?.taskId)
        }

        @Test
        fun `reads a partial sentence snapshot`() {
            val event = DashScopeAsrProtocol.parse(
                """
                {"header":{"event":"result-generated","task_id":"task-1"},
                 "payload":{"output":{"sentence":{"sentence_id":1,"text":"帮我看一下","sentence_begin":true,
                 "sentence_end":false,"begin_time":160,"end_time":null}}}}
                """.trimIndent()
            )
            assertEquals(AsrEventKind.SENTENCE, event?.kind)
            assertEquals(1, event?.sentenceId)
            assertEquals("帮我看一下", event?.text)
            assertEquals(false, event?.isFinal)
            assertEquals(160, event?.beginMs)
            assertNull(event?.endMs)
        }

        @Test
        fun `keeps a sentence begin with empty text so the order survives`() {
            val event = DashScopeAsrProtocol.parse(
                """
                {"header":{"event":"result-generated","task_id":"task-1"},
                 "payload":{"output":{"sentence":{"sentence_id":2,"text":"","sentence_begin":true,"sentence_end":false}}}}
                """.trimIndent()
            )
            assertEquals(AsrEventKind.SENTENCE, event?.kind)
            assertEquals(2, event?.sentenceId)
            assertEquals("", event?.text)
        }

        @Test
        fun `reads a final sentence`() {
            val event = DashScopeAsrProtocol.parse(
                """
                {"header":{"event":"result-generated","task_id":"task-1"},
                 "payload":{"output":{"sentence":{"sentence_id":1,"text":"帮我看一下英特尔。","sentence_end":true,
                 "begin_time":160,"end_time":2400}}}}
                """.trimIndent()
            )
            assertEquals(true, event?.isFinal)
            assertEquals(2400, event?.endMs)
        }

        @Test
        fun `treats a heartbeat as a heartbeat, not as sentence zero`() {
            val event = DashScopeAsrProtocol.parse(
                """{"header":{"event":"result-generated","task_id":"task-1","heartbeat":true},"payload":{}}"""
            )
            assertEquals(AsrEventKind.HEARTBEAT, event?.kind)
        }

        @Test
        fun `treats the documented sentence level heartbeat as a heartbeat`() {
            // The server puts the flag inside `sentence` and pins sentence_id to 0; a frame like this
            // must not reach the handler as a sentence, or it would count against the text limit.
            val event = DashScopeAsrProtocol.parse(
                """
                {"header":{"event":"result-generated","task_id":"task-1"},
                 "payload":{"output":{"sentence":{"sentence_id":0,"text":"","heartbeat":true}}}}
                """.trimIndent()
            )
            assertEquals(AsrEventKind.HEARTBEAT, event?.kind)
        }

        @Test
        fun `reads the final usage and its nullable fields`() {
            val event = DashScopeAsrProtocol.parse(
                """
                {"header":{"event":"task-finished","task_id":"task-1"},"payload":{"output":{},
                 "usage":{"input_tokens":365,"output_tokens":12,"total_tokens":377}}}
                """.trimIndent()
            )
            assertEquals(AsrEventKind.FINISHED, event?.kind)
            assertEquals(365, event?.usage?.inputTokens)
            assertEquals(12, event?.usage?.outputTokens)
            assertEquals(377, event?.usage?.totalTokens)
            assertNull(event?.usage?.durationSeconds)
        }

        @Test
        fun `reads a 3_0 duration without inventing token counts`() {
            val event = DashScopeAsrProtocol.parse(
                """{"header":{"event":"task-finished","task_id":"task-1"},"payload":{"usage":{"duration":12.5}}}"""
            )
            assertEquals(12.5, event?.usage?.durationSeconds)
            assertNull(event?.usage?.inputTokens)
            assertNull(event?.usage?.totalTokens)
        }

        @Test
        fun `reads a failure with its code`() {
            val event = DashScopeAsrProtocol.parse(
                """
                {"header":{"event":"task-failed","task_id":"task-1","error_code":"InvalidParameter",
                 "error_message":"vad_model is not supported"},"payload":{}}
                """.trimIndent()
            )
            assertEquals(AsrEventKind.FAILED, event?.kind)
            assertEquals("InvalidParameter", event?.errorCode)
        }

        @Test
        fun `ignores an event this build does not know`() {
            assertNull(DashScopeAsrProtocol.parse("""{"header":{"event":"result-updated"},"payload":{}}"""))
        }

        @Test
        fun `rejects malformed json and a missing event rather than dropping text`() {
            assertFailsWith<SpeechRecognitionException> { DashScopeAsrProtocol.parse("not json") }
            assertFailsWith<SpeechRecognitionException> { DashScopeAsrProtocol.parse("""{"header":{}}""") }
        }
    }
}
