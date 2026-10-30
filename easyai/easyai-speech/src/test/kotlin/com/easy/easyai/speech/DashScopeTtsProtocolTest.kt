package com.easy.easyai.speech

import com.easy.easyai.common.util.SharedObjectMapper
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the duplex wire codec. These are the frames that actually go on the socket, so each shape is
 * asserted against the documented protocol rather than against this implementation's own output.
 */
class DashScopeTtsProtocolTest {

    private fun parse(json: String) = SharedObjectMapper.instance.readTree(json)

    @Nested
    inner class Frames {

        @Test
        fun `run-task carries an empty input and the audio task triple`() {
            val node = parse(DashScopeTtsProtocol.runTask("task-1", "qwen-audio-3.1-tts-flash", mapOf("voice" to "longanhuan_v3.1")))

            val header = node.path("header")
            assertEquals("run-task", header.path("action").asString(null))
            assertEquals("task-1", header.path("task_id").asString(null))
            assertEquals("duplex", header.path("streaming").asString(null))

            val payload = node.path("payload")
            assertEquals("audio", payload.path("task_group").asString(null))
            assertEquals("tts", payload.path("task").asString(null))
            assertEquals("SpeechSynthesizer", payload.path("function").asString(null))
            assertEquals("qwen-audio-3.1-tts-flash", payload.path("model").asString(null))
            // Text is rejected here: it may only arrive through continue-task.
            assertTrue(payload.path("input").isObject)
            assertEquals(0, payload.path("input").size())
            assertEquals("PlainText", payload.path("parameters").path("text_type").asString(null))
            assertEquals("longanhuan_v3.1", payload.path("parameters").path("voice").asString(null))
        }

        @Test
        fun `run-task forces PlainText even when a caller supplies another type`() {
            // The protocol rejects text_type=SSML; enable_ssml is the only SSML switch.
            val node = parse(DashScopeTtsProtocol.runTask("t", "m", mapOf("text_type" to "SSML")))
            assertEquals("PlainText", node.path("payload").path("parameters").path("text_type").asString(null))
        }

        @Test
        fun `continue-task nests the text under payload input`() {
            val node = parse(DashScopeTtsProtocol.continueTask("task-2", "床前明月光"))
            assertEquals("continue-task", node.path("header").path("action").asString(null))
            assertEquals("task-2", node.path("header").path("task_id").asString(null))
            assertEquals("床前明月光", node.path("payload").path("input").path("text").asString(null))
        }

        @Test
        fun `continue-task escapes text rather than breaking the envelope`() {
            val tricky = """He said "hi" \ and {braces}"""
            val node = parse(DashScopeTtsProtocol.continueTask("t", tricky))
            assertEquals(tricky, node.path("payload").path("input").path("text").asString(null))
        }

        @Test
        fun `finish-task sends an empty input when the text is complete`() {
            val node = parse(DashScopeTtsProtocol.finishTask("task-3"))
            assertEquals("finish-task", node.path("header").path("action").asString(null))
            assertTrue(node.path("payload").path("input").isObject)
            assertEquals(0, node.path("payload").path("input").size())
        }

        @Test
        fun `finish-task sends the cancel directive to stop a task`() {
            val node = parse(DashScopeTtsProtocol.finishTask("task-4", cancel = true))
            assertEquals("cancel", node.path("payload").path("input").path("directive").asString(null))
        }
    }

    @Nested
    inner class Events {

        @Test
        fun `task-started is recognised`() {
            val event = DashScopeTtsProtocol.parseEvent(
                """{"header":{"task_id":"t","event":"task-started"},"payload":{}}"""
            )
            assertEquals("task-started", event.type)
            assertFalse(event.isFailure)
            assertFalse(event.isTerminal)
            assertTrue(DashScopeTtsProtocol.isKnownEvent(event))
        }

        @Test
        fun `result-generated exposes the sentence sub-type`() {
            val event = DashScopeTtsProtocol.parseEvent(
                """{"header":{"event":"result-generated"},"payload":{"output":{"type":"sentence-synthesis"}}}"""
            )
            assertEquals("result-generated", event.type)
            assertEquals("sentence-synthesis", event.sentenceType)
        }

        @Test
        fun `task-failed keeps both provider fields for the error message`() {
            val event = DashScopeTtsProtocol.parseEvent(
                """{"header":{"event":"task-failed","error_code":"InvalidParameter",
                   "error_message":"[tts:]Engine return error code: 418"},"payload":{}}"""
            )
            assertTrue(event.isFailure)
            assertTrue(event.isTerminal)
            assertEquals("InvalidParameter: [tts:]Engine return error code: 418", event.failureMessage())
        }

        @Test
        fun `task-finished is terminal but not a failure`() {
            val event = DashScopeTtsProtocol.parseEvent(
                """{"header":{"event":"task-finished"},"payload":{"usage":{"characters":13}}}"""
            )
            assertTrue(event.isTerminal)
            assertTrue(event.isFinished)
            assertFalse(event.isFailure)
        }

        @Test
        fun `a malformed frame degrades to an unrecognised event instead of throwing`() {
            assertNull(DashScopeTtsProtocol.parseEvent("not json").type)
            assertNull(DashScopeTtsProtocol.parseEvent("").type)
            // Degrading the *parse* is not the same as skipping the frame: the transport fails on it.
            assertFalse(DashScopeTtsProtocol.isKnownEvent(DashScopeTtsProtocol.parseEvent("not json")))
        }

        @Test
        fun `an undocumented event is not mistaken for a known one`() {
            val event = DashScopeTtsProtocol.parseEvent("""{"header":{"event":"task-queued"},"payload":{}}""")
            assertEquals("task-queued", event.type)
            assertFalse(DashScopeTtsProtocol.isKnownEvent(event))
        }

        @Test
        fun `a failure without fields still yields a readable message`() {
            val event = DashScopeTtsProtocol.Event("task-failed")
            assertTrue(event.failureMessage().contains("without an error message"))
        }
    }

    @Nested
    inner class Chunking {

        @Test
        fun `short text stays in one frame`() {
            assertEquals(listOf("你好世界"), DashScopeTtsProtocol.chunkText("你好世界"))
        }

        @Test
        fun `long text is cut at a sentence ender near the limit`() {
            val text = "句子。".repeat(10)
            val chunks = DashScopeTtsProtocol.chunkText(text, limit = 10)
            assertTrue(chunks.size > 1)
            assertEquals(text, chunks.joinToString(""))
            // The cut lands after the last terminator inside the 10-char window, i.e. 9 chars in.
            assertEquals("句子。句子。句子。", chunks.first())
        }

        @Test
        fun `hard-cuts when no sentence ender is available`() {
            val text = "a".repeat(25)
            val chunks = DashScopeTtsProtocol.chunkText(text, limit = 10)
            assertEquals(listOf("a".repeat(10), "a".repeat(10), "a".repeat(5)), chunks)
        }

        @Test
        fun `never splits a surrogate pair`() {
            val emoji = "😀"
            val text = "a".repeat(19_999) + emoji + "b".repeat(5)
            val chunks = DashScopeTtsProtocol.chunkText(text, limit = 20_000)
            assertFalse(Character.isHighSurrogate(chunks.first().last()))
            assertEquals(text, chunks.joinToString(""))
        }

        @Test
        fun `single-frame mode keeps SSML whole`() {
            val text = "<speak>你好</speak>".repeat(5)
            assertEquals(listOf(text), DashScopeTtsProtocol.chunkText(text, limit = 10, singleFrame = true))
        }
    }

    @Nested
    inner class Endpoint {

        @Test
        fun `rewrites the documented api root to the duplex path`() {
            assertEquals(
                "wss://ws-123.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
                DashScopeTtsProtocol.endpoint("https://ws-123.cn-beijing.maas.aliyuncs.com/api/v1")
            )
        }

        @Test
        fun `accepts every base shape a user might paste`() {
            val expected = "wss://ws-123.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference"
            listOf(
                "https://ws-123.cn-beijing.maas.aliyuncs.com/api/v1",
                "https://ws-123.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer",
                "https://ws-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                "https://ws-123.cn-beijing.maas.aliyuncs.com",
                "wss://ws-123.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference"
            ).forEach { assertEquals(expected, DashScopeTtsProtocol.endpoint(it), it) }
        }

        @Test
        fun `maps http to ws for a local gateway`() {
            assertEquals("ws://localhost:8080/api-ws/v1/inference", DashScopeTtsProtocol.endpoint("http://localhost:8080/api/v1"))
            assertEquals("ws://127.0.0.1:8080/api-ws/v1/inference", DashScopeTtsProtocol.endpoint("http://127.0.0.1:8080/api/v1"))
            // A bracketed IPv6 literal carries colons of its own; the port colon is not the first one.
            assertEquals("ws://[::1]:8080/api-ws/v1/inference", DashScopeTtsProtocol.endpoint("http://[::1]:8080/api/v1"))
        }

        @Test
        fun `never downgrades a remote host to plaintext`() {
            assertEquals(
                "wss://ws-123.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
                DashScopeTtsProtocol.endpoint("http://ws-123.cn-beijing.maas.aliyuncs.com/api/v1")
            )
            assertEquals(
                "wss://ws-123.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
                DashScopeTtsProtocol.endpoint("ws://ws-123.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference")
            )
        }

        @Test
        fun `blank base is a configuration error`() {
            assertFailsWith<IllegalArgumentException> { DashScopeTtsProtocol.endpoint("  ") }
        }
    }

    @Nested
    inner class AudioAssembly {

        private fun wavFrame(payload: ByteArray): ByteArray = wavContainer(payload) + payload

        @Test
        fun `mp3 frames concatenate byte for byte`() {
            val sink = ByteArrayOutputStream()
            DashScopeTtsProtocol.appendAudio(sink, byteArrayOf(1, 2, 3), "mp3")
            DashScopeTtsProtocol.appendAudio(sink, byteArrayOf(4, 5), "mp3")
            assertEquals(listOf<Byte>(1, 2, 3, 4, 5), sink.toByteArray().toList())
        }

        @Test
        fun `wav keeps the first header and strips repeated ones`() {
            val sink = ByteArrayOutputStream()
            DashScopeTtsProtocol.appendAudio(sink, wavFrame(byteArrayOf(1, 2)), "wav")
            DashScopeTtsProtocol.appendAudio(sink, wavFrame(byteArrayOf(3, 4)), "wav")

            val result = sink.toByteArray()
            assertEquals(44 + 2 + 2, result.size)
            assertEquals("RIFF", String(result, 0, 4, Charsets.US_ASCII))
            assertEquals(listOf<Byte>(1, 2, 3, 4), result.copyOfRange(44, result.size).toList())
        }

        @Test
        fun `wav frames without a header are appended raw`() {
            val sink = ByteArrayOutputStream()
            DashScopeTtsProtocol.appendAudio(sink, wavFrame(byteArrayOf(9)), "wav")
            DashScopeTtsProtocol.appendAudio(sink, byteArrayOf(7, 8), "wav")
            assertEquals(listOf<Byte>(9, 7, 8), sink.toByteArray().copyOfRange(44, 47).toList())
        }

        @Test
        fun `wav header offset is found past a LIST chunk`() {
            val payload = byteArrayOf(5, 6, 7)
            fun sizeOf(value: Int) = ByteArray(4).also { writeIntLe(it, 0, value) }

            val fmt = "fmt ".toByteArray() + sizeOf(16) + ByteArray(16)
            val list = "LIST".toByteArray() + sizeOf(8) + "INFOISFT".toByteArray()
            val body = "WAVE".toByteArray() + fmt + list + "data".toByteArray() + sizeOf(payload.size) + payload
            val frame = "RIFF".toByteArray() + sizeOf(body.size) + body

            assertEquals(frame.size - payload.size, DashScopeTtsProtocol.wavDataOffset(frame))
            assertEquals(listOf<Byte>(5, 6, 7), frame.copyOfRange(60, 63).toList())
        }

        @Test
        fun `non-riff data is not treated as a wav container`() {
            assertNull(DashScopeTtsProtocol.wavDataOffset(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)))
        }

        @Test
        fun `finalizing a wav rewrites both RIFF and data sizes`() {
            val sink = ByteArrayOutputStream()
            DashScopeTtsProtocol.appendAudio(sink, wavFrame(ByteArray(10) { 1 }), "wav")
            DashScopeTtsProtocol.appendAudio(sink, wavFrame(ByteArray(20) { 2 }), "wav")

            // Before the fix the container still describes the first frame only.
            val raw = sink.toByteArray()
            assertEquals(44 + 10, DashScopeTtsProtocol.readIntLe(raw, 4) + 8)
            assertEquals(10, DashScopeTtsProtocol.readIntLe(raw, 40))

            val fixed = DashScopeTtsProtocol.finalizeAudio(raw, "wav")
            assertEquals(fixed.size - 8, DashScopeTtsProtocol.readIntLe(fixed, 4))
            assertEquals(30, DashScopeTtsProtocol.readIntLe(fixed, 40))
            // Exactly one container header survives, and the samples are contiguous.
            assertEquals(1, countRiff(fixed))
            assertEquals(listOf<Byte>(1, 1), fixed.copyOfRange(44, 46).toList())
            assertEquals(listOf<Byte>(2, 2), fixed.copyOfRange(54, 56).toList())
        }

        @Test
        fun `size rewriting follows a data chunk that is not at offset 36`() {
            fun sizeOf(value: Int) = ByteArray(4).also { writeIntLe(it, 0, value) }
            val payload = ByteArray(6) { 3 }
            val fmt = "fmt ".toByteArray() + sizeOf(16) + ByteArray(16)
            val body = "WAVE".toByteArray() + fmt + "data".toByteArray() + sizeOf(payload.size) + payload
            val frame = "RIFF".toByteArray() + sizeOf(body.size) + body

            val sink = ByteArrayOutputStream()
            DashScopeTtsProtocol.appendAudio(sink, frame, "wav")
            DashScopeTtsProtocol.appendAudio(sink, frame, "wav")

            val fixed = DashScopeTtsProtocol.finalizeAudio(sink.toByteArray(), "wav")
            val dataOffset = DashScopeTtsProtocol.wavDataOffset(fixed)!!
            assertEquals(fixed.size - 8, DashScopeTtsProtocol.readIntLe(fixed, 4))
            assertEquals(fixed.size - dataOffset, DashScopeTtsProtocol.readIntLe(fixed, dataOffset - 4))
        }

        @Test
        fun `finalize leaves other formats and headerless wav alone`() {
            val mp3 = byteArrayOf(1, 2, 3, 4, 5)
            assertTrue(DashScopeTtsProtocol.finalizeAudio(mp3, "mp3").contentEquals(mp3))
            val raw = ByteArray(20) { 7 }
            assertTrue(DashScopeTtsProtocol.finalizeAudio(raw, "wav").contentEquals(raw))
        }

        private fun countRiff(bytes: ByteArray): Int {
            var count = 0
            for (i in 0..bytes.size - 4) {
                if (bytes[i] == 'R'.code.toByte() && bytes[i + 1] == 'I'.code.toByte() &&
                    bytes[i + 2] == 'F'.code.toByte() && bytes[i + 3] == 'F'.code.toByte()
                ) count++
            }
            return count
        }
    }

    private companion object {
        fun writeIntLe(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value and 0xFF).toByte()
            target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        /** A 44-byte canonical WAV header claiming [payload] length; the payload is appended by callers. */
        fun wavContainer(payload: ByteArray): ByteArray {
            val header = ByteArray(44)
            "RIFF".toByteArray().copyInto(header, 0)
            writeIntLe(header, 4, 36 + payload.size)
            "WAVE".toByteArray().copyInto(header, 8)
            "fmt ".toByteArray().copyInto(header, 12)
            writeIntLe(header, 16, 16)
            "data".toByteArray().copyInto(header, 36)
            writeIntLe(header, 40, payload.size)
            return header
        }
    }
}
