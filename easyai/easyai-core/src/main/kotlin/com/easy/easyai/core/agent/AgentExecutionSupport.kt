package com.easy.easyai.core.agent

import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.EventStream
import com.easy.easyai.core.event.MessageEndEvent
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.Usage
import com.easy.easyai.core.model.UserMessage
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Status of an isolated agent execution run.
 */
enum class ExecutionStatus {
    COMPLETED, FAILED, TIMEOUT
}

/**
 * Output of an isolated agent execution run.
 *
 * Callers map this to their domain-specific result type:
 * - `SwarmWorkerExecutor` → `WorkerResult`
 * - `SubAgentTool` → `ToolResult`
 *
 * @property status Execution outcome
 * @property summary Last assistant message text (truncated to maxSummaryLength if needed)
 * @property usage Accumulated token usage from all MessageEndEvents
 * @property error Human-readable error description (non-null when [status] is FAILED or TIMEOUT)
 * @property label Caller-provided label for log messages (e.g. "Worker 'researcher'", "SubAgent 'coder'")
 */
data class AgentExecutionOutput(
    val status: ExecutionStatus,
    val summary: String,
    val usage: Usage,
    val error: String? = null,
    val label: String = "Agent",
) {
    /** True when at least one token was consumed — used to decide whether to attach usage to error results. */
    val hasUsage: Boolean
        get() = usage.inputTokens > 0 || usage.outputTokens > 0
}

/**
 * Shared execution infrastructure for isolated agent runs.
 *
 * Encapsulates the full [AgentRunner] lifecycle that was previously duplicated between
 * `SwarmWorkerExecutor.executeWorker()` and `SubAgentTool.doExecute()`:
 * - `CompletableDeferred<Job>` + `AgentRunner` creation
 * - `withTimeout` + `stream.asFlow().collect` with `MessageEndEvent` usage accumulation
 * - Three-way exception handling (`TimeoutCancellationException` / `CancellationException` / `Exception`)
 *   with `stream?.cancel()` + `jobDeferred.cancel()` in each error path
 * - Last-text-message extraction with configurable truncation
 *
 * Callers differ only in:
 * - Timeout calculation (passed as timeoutMs)
 * - Event forwarding strategy (passed as onEvent callback)
 * - Result type mapping (caller maps [AgentExecutionOutput] → domain type)
 */

private val logger = LoggerFactory.getLogger("com.easy.easyai.core.agent.AgentExecutionSupport")

/**
 * Execute an agent with timeout protection, usage tracking, and cancellation safety.
 *
 * @param agent Fully-configured [Agent] (context + services already resolved)
 * @param prompt User message prompt text
 * @param timeoutMs Maximum execution duration in milliseconds
 * @param abortSignal Optional signal to request graceful cancellation
 * @param onEvent Callback invoked for each emitted [AgentEvent];
 *   callers use this to forward events to their own event system (swarm SSE, tool update, etc.)
 * @param maxSummaryLength Maximum characters for the extracted summary; longer text is truncated
 * @param truncateLabel Label used in truncation notice (e.g. "Summary" or "Result")
 * @param label Descriptive label for log messages (e.g. `"Worker 'researcher'"`)
 * @return [AgentExecutionOutput] with COMPLETED, FAILED, or TIMEOUT status
 */
suspend fun executeAgentWithProtection(
    agent: Agent,
    prompt: String,
    timeoutMs: Long,
    abortSignal: () -> Boolean = { false },
    onEvent: suspend (AgentEvent) -> Unit = {},
    maxSummaryLength: Int = 10_000,
    truncateLabel: String = "Summary",
    label: String = "Agent",
    initialMessages: List<EasyAiMessage> = emptyList(),
): AgentExecutionOutput {
    val jobDeferred = CompletableDeferred<Job>()
    val runner = AgentRunner(
        agent = agent,
        messages = initialMessages.toMutableList(),
        abortSignal = abortSignal,
        registerJob = { job -> job?.let { jobDeferred.complete(it) } }
    )

    var partialUsage = Usage()
    // Hoist stream reference for cancellation in timeout/exception paths
    var stream: EventStream<*, *>? = null

    val resultMessages = try {
        withTimeout(timeoutMs.milliseconds) {
            val effectivePrompt = prompt.ifBlank {
                "Please proceed with your configured task."
            }
            val s = runner.prompt(listOf(UserMessage(effectivePrompt)))
            stream = s
            s.asFlow().collect { event ->
                // Accumulate usage from every MessageEndEvent (independent of caller's event forwarding)
                if (event is MessageEndEvent && event.usage != null) {
                    val u = event.usage
                    partialUsage = Usage(
                        inputTokens = partialUsage.inputTokens + u.inputTokens,
                        outputTokens = partialUsage.outputTokens + u.outputTokens,
                        cacheReadTokens = partialUsage.cacheReadTokens + u.cacheReadTokens,
                        cacheWriteTokens = partialUsage.cacheWriteTokens + u.cacheWriteTokens,
                        cost = partialUsage.cost + u.cost,
                        durationMs = partialUsage.durationMs + u.durationMs
                    )
                }
                // Delegate event forwarding to caller
                onEvent(event)
            }
            s.result()
        }
    } catch (_: TimeoutCancellationException) {
        stream?.cancel()
        jobDeferred.cancel()
        logger.warn("{} timed out after {}ms", label, timeoutMs)
        return AgentExecutionOutput(
            status = ExecutionStatus.TIMEOUT,
            summary = "",
            usage = partialUsage,
            error = "$label timed out after ${timeoutMs}ms",
            label = label,
        )
    } catch (e: CancellationException) {
        // Parent coroutine cancelled — explicitly cancel the EventStream's independent
        // producer coroutine to prevent orphaned execution (SupervisorJob does not auto-propagate)
        stream?.cancel()
        jobDeferred.cancel()
        throw e
    } catch (e: Exception) {
        stream?.cancel()
        logger.error("{} failed: {}", label, e.message, e)
        return AgentExecutionOutput(
            status = ExecutionStatus.FAILED,
            summary = "",
            usage = partialUsage,
            error = "$label failed: ${e.message}",
            label = label,
        )
    }

    // Extract last text message as summary — only the final assistant message is the conclusion;
    // intermediate messages are thinking/tool-use steps irrelevant to the caller.
    val lastText = resultMessages.lastOrNull()?.text().orEmpty()
    if (lastText.isBlank()) {
        logger.warn("{} produced no output", label)
        return AgentExecutionOutput(
            status = ExecutionStatus.FAILED,
            summary = "",
            usage = partialUsage,
            error = "$label produced no output",
            label = label,
        )
    }

    val summary = truncateSummary(lastText, maxSummaryLength, truncateLabel)
    return AgentExecutionOutput(
        status = ExecutionStatus.COMPLETED,
        summary = summary,
        usage = partialUsage,
        label = label,
    )
}

/**
 * Truncate text to [maxLength] characters, appending a notice when truncation occurs.
 */
fun truncateSummary(text: String, maxLength: Int, label: String = "Summary"): String =
    if (text.length > maxLength) {
        text.take(maxLength) + "\n\n[$label truncated — ${text.length} chars total]"
    } else {
        text
    }

/**
 * Wrap an [AgentService] with an optional [MessageListener] for real-time message persistence.
 *
 * Eliminates the duplicated `AgentService by delegate` wrapper pattern previously present
 * as `SwarmWorkerAgentService` (swarm) and `wrapServiceWithSubAgentListener` (sub-agent).
 *
 * @return [delegate] unchanged when [listener] is null; otherwise a delegating wrapper
 *   that overrides [AgentService.messageListener] with [listener].
 */
fun wrapServiceWithListener(delegate: AgentService, listener: MessageListener?): AgentService =
    if (listener == null) delegate
    else object : AgentService by delegate {
        override val messageListener: MessageListener = listener
    }

/**
 * Wrap an [AgentService] with additional [AgentCompletionCheck]s.
 *
 * Follows the same delegate-wrapper pattern as [wrapServiceWithListener].
 * Returns [delegate] unchanged when [additionalChecks] is empty.
 */
fun wrapServiceWithCompletionChecks(
    delegate: AgentService,
    additionalChecks: List<AgentCompletionCheck>
): AgentService = if (additionalChecks.isEmpty()) delegate
    else object : AgentService by delegate {
        override val completionChecks: List<AgentCompletionCheck> = delegate.completionChecks + additionalChecks
    }
