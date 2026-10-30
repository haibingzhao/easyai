package com.easy.easyai.speech

import com.easy.easyai.common.util.SharedObjectMapper
import tools.jackson.databind.JsonNode

/**
 * Wire codec for the DashScope-native recognition duplex protocol.
 *
 * The same shape drives every Qwen-Audio ASR model: `run-task` opens the task, raw PCM arrives as
 * binary frames, `result-generated` carries sentence snapshots, `finish-task` closes the task and
 * the service keeps sending trailing sentences until `task-finished`. Nothing is base64-wrapped and
 * there is no `continue-task` in the recognition path this module implements.
 *
 * Everything positional is a pure function so the frames can be asserted without a socket; the
 * transport in [WebSocketAsrTransport] only moves bytes.
 */
internal object DashScopeAsrProtocol {

    const val TASK_GROUP = "audio"
    const val TASK = "asr"
    const val FUNCTION = "recognition"

    /** Capture is fixed at 16 kHz mono PCM16; the request must say exactly what the bytes are. */
    const val FORMAT = "pcm"
    const val SAMPLE_RATE = 16000

    /**
     * Keep the session warm during silence.
     *
     * The service expects real silent audio alongside this flag: a heartbeat flag alone does not
     * keep the timeline honest, and the browser streams every sample (silence included) for exactly
     * that reason.
     */
    const val HEARTBEAT = true

    /**
     * Build `run-task`.
     *
     * [parameters] are the model-specific extras the caller already validated (VAD mode, silence
     * threshold, generation-specific switches). The transport-level fields are written last so a
     * caller cannot disagree with the bytes actually being sent.
     */
    fun runTask(taskId: String, model: String, parameters: Map<String, Any?>): String {
        require(taskId.isNotBlank()) { "task id is required" }
        require(model.isNotBlank()) { "model id is required" }
        val effective = LinkedHashMap<String, Any?>()
        effective.putAll(parameters)
        effective["format"] = FORMAT
        effective["sample_rate"] = SAMPLE_RATE
        effective["heartbeat"] = HEARTBEAT
        val body = linkedMapOf(
            "header" to linkedMapOf(
                "action" to "run-task",
                "task_id" to taskId,
                "streaming" to "duplex"
            ),
            "payload" to linkedMapOf(
                "task_group" to TASK_GROUP,
                "task" to TASK,
                "function" to FUNCTION,
                "model" to model,
                "parameters" to effective,
                "input" to emptyMap<String, Any>()
            )
        )
        return SharedObjectMapper.instance.writeValueAsString(body)
    }

    /**
     * Build `finish-task`: the last audio frame has been sent, flush and report the tail.
     *
     * There is no `directive=cancel` in this protocol; cancelling means closing the socket, so no
     * such field is emitted here.
     */
    fun finishTask(taskId: String): String {
        require(taskId.isNotBlank()) { "task id is required" }
        val body = linkedMapOf(
            "header" to linkedMapOf(
                "action" to "finish-task",
                "task_id" to taskId,
                "streaming" to "duplex"
            ),
            "payload" to linkedMapOf("input" to emptyMap<String, Any>())
        )
        return SharedObjectMapper.instance.writeValueAsString(body)
    }

    /**
     * Parse one server frame.
     *
     * Returns null for an event this build does not know, so a forward-compatible addition cannot
     * break a running session. Malformed JSON, a missing `header.event`, or a negative sentence id
     * throws [SpeechRecognitionException] with [SpeechRecognitionException.PROTOCOL_ERROR]: those
     * are not "unknown", they are unreadable, and continuing would silently drop text.
     */
    fun parse(text: String): AsrUpstreamEvent? {
        val root = try {
            SharedObjectMapper.instance.readTree(text)
        } catch (e: Exception) {
            throw SpeechRecognitionException(
                SpeechRecognitionException.PROTOCOL_ERROR,
                "unreadable ASR frame (${text.length} chars)",
                e
            )
        }
        val header = root.path("header")
        val event = stringOrNull(header, "event")
            ?: throw SpeechRecognitionException(
                SpeechRecognitionException.PROTOCOL_ERROR,
                "ASR frame without header.event"
            )
        val taskId = stringOrNull(header, "task_id")
        val usage = parseUsage(root.path("payload").path("usage"))
        return when (event) {
            "task-started" -> AsrUpstreamEvent(kind = AsrEventKind.STARTED, taskId = taskId)
            "result-generated" -> parseResult(header, root, taskId, usage)
            "task-finished" -> AsrUpstreamEvent(kind = AsrEventKind.FINISHED, taskId = taskId, usage = usage)
            "task-failed" -> AsrUpstreamEvent(
                kind = AsrEventKind.FAILED,
                taskId = taskId,
                errorCode = stringOrNull(header, "error_code") ?: "UNKNOWN",
                errorMessage = stringOrNull(header, "error_message").orEmpty()
            )

            else -> null
        }
    }

    private fun parseResult(
        header: JsonNode,
        root: JsonNode,
        taskId: String?,
        usage: SpeechRecognitionUsage?
    ): AsrUpstreamEvent {
        // The heartbeat frame carries no sentence. Checking the flag first matters: a heartbeat can
        // arrive while sentence 1 is still open, and treating it as a sentence would create a
        // phantom slot or truncate the live one.
        //
        // The documented location is `payload.output.sentence.heartbeat` (with `sentence_id` pinned to
        // 0); the `header.heartbeat` check only covers older/echoed frames that put it there.
        val sentence = root.path("payload").path("output").path("sentence")
        val heartbeat = booleanOrNull(header, "heartbeat") == true || booleanOrNull(sentence, "heartbeat") == true
        if (heartbeat || sentence.isMissingNode || sentence.isNull) {
            return AsrUpstreamEvent(kind = AsrEventKind.HEARTBEAT, taskId = taskId, usage = usage)
        }
        val sentenceId = intOrNull(sentence, "sentence_id") ?: 0
        if (sentenceId < 0) {
            throw SpeechRecognitionException(
                SpeechRecognitionException.PROTOCOL_ERROR,
                "negative sentence id $sentenceId"
            )
        }
        // `sentence_begin` with an empty text still opens a slot: the order of sentences must not
        // depend on the service happening to send words in the same frame.
        return AsrUpstreamEvent(
            kind = AsrEventKind.SENTENCE,
            taskId = taskId,
            sentenceId = sentenceId,
            text = stringOrNull(sentence, "text").orEmpty(),
            isFinal = booleanOrNull(sentence, "sentence_end") == true,
            beginMs = intOrNull(sentence, "begin_time"),
            endMs = intOrNull(sentence, "end_time"),
            usage = usage
        )
    }

    /** Usage fields are cumulative per task; a frame that omits them contributes nothing. */
    fun parseUsage(node: JsonNode): SpeechRecognitionUsage? {
        if (node.isMissingNode || node.isNull || !node.isObject) return null
        return SpeechRecognitionUsage(
            inputTokens = intOrNull(node, "input_tokens"),
            outputTokens = intOrNull(node, "output_tokens"),
            totalTokens = intOrNull(node, "total_tokens"),
            durationSeconds = doubleOrNull(node, "duration")
        ).takeIf { !it.isEmpty }
    }

    private fun stringOrNull(node: JsonNode, field: String): String? {
        val value = node.path(field)
        return when {
            value.isMissingNode || value.isNull -> null
            value.isString -> value.asString()
            else -> null
        }
    }

    private fun intOrNull(node: JsonNode, field: String): Int? {
        val value = node.path(field)
        return if (value.isNumber) value.intValue() else null
    }

    private fun doubleOrNull(node: JsonNode, field: String): Double? {
        val value = node.path(field)
        return if (value.isNumber) value.doubleValue() else null
    }

    private fun booleanOrNull(node: JsonNode, field: String): Boolean? {
        val value = node.path(field)
        return if (value.isBoolean) value.booleanValue() else null
    }
}

internal enum class AsrEventKind { STARTED, SENTENCE, HEARTBEAT, FINISHED, FAILED }

/** One decoded server frame; [taskId] lets the transport reject frames from another task. */
internal data class AsrUpstreamEvent(
    val kind: AsrEventKind,
    val taskId: String? = null,
    val sentenceId: Int = 0,
    val text: String = "",
    val isFinal: Boolean = false,
    val beginMs: Int? = null,
    val endMs: Int? = null,
    val usage: SpeechRecognitionUsage? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
)
