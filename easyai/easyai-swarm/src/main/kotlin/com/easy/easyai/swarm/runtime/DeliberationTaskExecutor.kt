package com.easy.easyai.swarm.runtime

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.swarm.event.SwarmEventBridge
import com.easy.easyai.swarm.model.*
import com.easy.easyai.swarm.store.SwarmRunStore
import org.slf4j.LoggerFactory

/**
 * Executes DELIBERATION tasks: multi-agent iterative collaboration with Judge as Orchestrator.
 *
 * The Judge agent dynamically generates prompts for participants at runtime:
 * 1. Opening round: Judge generates an opening prompt, all participants respond
 * 2. Subsequent rounds: Judge reviews history and generates personalized prompts per participant
 * 3. Convergence: Judge autonomously decides when consensus is reached
 * 4. Verdict: Judge renders final verdict based on full deliberation history
 *
 * Extracted from [SwarmRuntime] to reduce its size and isolate deliberation logic.
 */
internal class DeliberationTaskExecutor(
    private val workerExecutor: SwarmWorkerExecutor,
    private val eventBridge: SwarmEventBridge,
    private val store: SwarmRunStore? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = SharedObjectMapper.instance

    companion object {
        /**
         * JSON Schema for Judge round prompt generation output.
         * Uses array format (instead of dynamic keys) for OpenAI strict mode compatibility.
         */
        val ROUND_PROMPTS_SCHEMA = """
{
  "type": "object",
  "properties": {
    "converged": { "type": "boolean" },
    "reason": { "type": "string" },
    "prompts": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "participantId": { "type": "string" },
          "prompt": { "type": "string" }
        },
        "required": ["participantId", "prompt"],
        "additionalProperties": false
      }
    }
  },
  "required": ["converged", "reason"],
  "additionalProperties": false
}
        """.trimIndent()

        /**
         * Neutral orchestrator SystemMessage used for Judge's Opening and Round prompt generation phases.
         * Replaces the Judge's own promptTemplate to prevent persona/profession bias during orchestration.
         * The Verdict phase retains the Judge's own promptTemplate since judging is its intended role.
         */
        val ORCHESTRATOR_SYSTEM_PROMPT = """
            |You are the orchestrator of a multi-agent deliberation session.
            |
            |Your responsibilities:
            |1. Generate clear, balanced prompts that guide participants through structured discussion
            |2. Objectively assess whether consensus has been reached based on the discussion history
            |3. When generating personalized prompts, reference specific prior arguments and direct participants to address unresolved points
            |
            |Be neutral, fair, and focused on productive deliberation outcomes.
            |Do not inject your own opinions or expertise — your role is to facilitate the discussion, not participate in it.
        """.trimMargin()
    }

    /**
     * Execute a DELIBERATION task: multi-agent iterative collaboration.
     */
    suspend fun runDeliberation(
        task: SwarmTask,
        run: SwarmRun,
        taskSummaries: Map<String, String>,
        abortSignal: () -> Boolean,
        runContext: RunContext
    ): WorkerResult {
        val deliberation = task.deliberation
            ?: return WorkerResult(SwarmTaskStatus.FAILED, "", error = "No deliberation spec for DELIBERATION task")

        eventBridge.onDeliberationStarted(run, task, deliberation)

        // Detect resume: deliberationHistory populated by SwarmRuntime.resume() from DB
        val isResume = task.deliberationHistory.isNotEmpty()
        val history = mutableListOf<DeliberationEntry>()
        val total = TokenCounters()

        if (isResume) {
            history.addAll(task.deliberationHistory)
            // Rebuild token counters from persisted history (same pattern as TEAM rebuildStateFromHistory)
            for (entry in history) {
                total.input += entry.inputTokens
                total.output += entry.outputTokens
                total.cacheRead += entry.cacheReadTokens
                total.cacheWrite += entry.cacheWriteTokens
                total.duration += entry.durationMs
            }
            logger.info("DELIBERATION task '{}' resuming ({} entries restored, max round {})",
                task.id, history.size, history.maxOfOrNull { it.round } ?: 0)
        }

        val inputFromVars = workerExecutor.resolveInputFrom(task, taskSummaries)
        val allParticipants = deliberation.participants.map { pid ->
            val spec = run.agents.find { it.id == pid }
            pid to (spec?.role ?: pid)
        }

        // Render the deliberation context from the user-configured template
        val deliberationContext = workerExecutor.renderPrompt(
            deliberation.contextTemplate, taskSummaries, run.userVars, inputFromVars, null
        )

        val judgeSpec = run.agents.find { it.id == deliberation.judge }
        if (judgeSpec == null) {
            val failedSnapshot = total.snapshot()
            return WorkerResult(
                SwarmTaskStatus.FAILED, "",
                inputTokens = failedSnapshot.input,
                outputTokens = failedSnapshot.output,
                cacheReadTokens = failedSnapshot.cacheRead,
                cacheWriteTokens = failedSnapshot.cacheWrite,
                durationMs = failedSnapshot.duration,
                error = "Judge agent '${deliberation.judge}' not found"
            )
        }
        // Build participant profiles for the Judge
        val participantProfiles = buildParticipantProfiles(allParticipants, runContext, run)

        // Determine resume point: find the last consecutively completed round
        val lastCompletedRound = if (isResume) {
            val roundCounts = history.groupBy { it.round }.mapValues { (_, entries) -> entries.size }
            val participantCount = deliberation.participants.size
            var last = 0
            for (round in 1..deliberation.maxRounds) {
                if ((roundCounts[round] ?: 0) >= participantCount) last = round
                else break // Stop at first incomplete round to avoid skipping gaps
            }
            last
        } else 0

        // --- Opening Round: Judge generates opening prompt (skip on resume if already in history) ---
        val openingPrompt: String
        val recoveredOpening = if (isResume) history.firstOrNull { it.openingPrompt != null }?.openingPrompt else null
        if (recoveredOpening != null) {
            // Opening prompt already persisted in history — recover directly (handles partial round 1)
            openingPrompt = recoveredOpening
            logger.info("Deliberation '{}' — skipping opening (recovered from history)", task.id)
        } else {
            logger.info("Deliberation '{}' — Judge generating opening prompt", task.id)

            val openingPromptForJudge = buildOpeningGenerationPrompt(
                deliberationContext, participantProfiles
            )
            val openingResult = workerExecutor.executeWorker(
                judgeSpec, openingPromptForJudge, run, task, runContext, abortSignal,
                systemPromptOverride = ORCHESTRATOR_SYSTEM_PROMPT
            )
            total += openingResult

            if (openingResult.status == SwarmTaskStatus.FAILED) {
                logger.error("Deliberation '{}' — Judge failed to generate opening prompt: {}", task.id, openingResult.error)
                val snapshot = total.snapshot()
                return WorkerResult(
                    SwarmTaskStatus.FAILED, "",
                    inputTokens = snapshot.input, outputTokens = snapshot.output,
                    cacheReadTokens = snapshot.cacheRead, cacheWriteTokens = snapshot.cacheWrite,
                    durationMs = snapshot.duration, error = "Judge failed to generate opening prompt: ${openingResult.error}",
                    deliberationHistory = history
                )
            }

            val rawOpeningPrompt = openingResult.summary
            openingPrompt = if (run.language.isNotBlank()) {
                rawOpeningPrompt + buildLanguageSegment(run.language)
            } else {
                rawOpeningPrompt
            }
            logger.info("Deliberation '{}' — Opening prompt generated ({} chars)", task.id, openingPrompt.length)
        }

        // --- Main deliberation loop (resume from the appropriate round) ---
        val startRound = (lastCompletedRound + 1).coerceAtLeast(1)
        if (startRound > deliberation.maxRounds) {
            logger.info("Deliberation '{}' — all rounds complete, proceeding to verdict", task.id)
        }

        for (round in startRound..deliberation.maxRounds) {
            if (abortSignal()) {
                logger.info("Deliberation '{}' aborted at round {} by abort signal", task.id, round)
                break
            }

            eventBridge.onDeliberationRoundStarted(run, task, round)
            logger.info("Deliberation '{}' round {}/{}", task.id, round, deliberation.maxRounds)

            // Rotate speaker order for ROUND_ROBIN
            val speakers = when (deliberation.order) {
                DeliberationOrder.ROUND_ROBIN -> {
                    val shift = (round - 1) % deliberation.participants.size
                    deliberation.participants.drop(shift) + deliberation.participants.take(shift)
                }
                DeliberationOrder.SEQUENTIAL -> deliberation.participants
            }

            // Determine prompts for this round
            val roundPrompts: Map<String, String> = if (round == 1) {
                // Opening round: all participants get the same judge-generated opening prompt
                speakers.associateWith { openingPrompt }
            } else {
                // Try to recover persisted prompts for partial round resume
                val persistedPrompts = if (isResume) {
                    history.firstOrNull { it.round == round && it.roundPrompts != null }?.roundPrompts
                } else null

                if (persistedPrompts != null) {
                    logger.info("Deliberation '{}' — recovered round {} prompts from history", task.id, round)
                    persistedPrompts
                } else {
                    // Subsequent rounds: Judge generates personalized prompts
                    logger.info("Deliberation '{}' — Judge generating round {} prompts", task.id, round)
                    val generatedPrompts = generateRoundPrompts(
                        judgeSpec, run, task, runContext, abortSignal, total,
                        history, round, participantProfiles, allParticipants,
                    )
                    if (generatedPrompts == null) {
                        // Judge decided to converge — break to verdict
                        logger.info("Deliberation '{}' — Judge signaled convergence at round {}", task.id, round)
                        break
                    }
                    // Inject language instruction into each participant prompt
                    if (run.language.isNotBlank()) {
                        val langSegment = buildLanguageSegment(run.language)
                        generatedPrompts.mapValues { (_, p) -> p + langSegment }
                    } else {
                        generatedPrompts
                    }
                }
            }

            // Handle partial round on resume: skip speakers who already responded
            val completedSpeakers = if (isResume) {
                history.filter { it.round == round }.map { it.agentId }.toSet()
            } else emptySet()

            // Each participant responds
            var isFirstSpeaker = true
            for (participantId in speakers) {
                if (participantId in completedSpeakers) {
                    logger.debug("Deliberation '{}' — skipping already-completed speaker '{}' in round {}",
                        task.id, participantId, round)
                    isFirstSpeaker = false
                    continue
                }

                val agentSpec = run.agents.find { it.id == participantId } ?: continue
                val prompt = roundPrompts[participantId] ?: openingPrompt

                val result = workerExecutor.executeWorker(
                    agentSpec, prompt, run, task, runContext, abortSignal
                )

                history.add(DeliberationEntry(
                    agentId = participantId,
                    round = round,
                    response = result.summary,
                    inputTokens = result.inputTokens,
                    outputTokens = result.outputTokens,
                    cacheReadTokens = result.cacheReadTokens,
                    cacheWriteTokens = result.cacheWriteTokens,
                    durationMs = result.durationMs,
                    // Attach prompt metadata to the first entry of each round
                    openingPrompt = if (isFirstSpeaker && round == 1) openingPrompt else null,
                    roundPrompts = if (isFirstSpeaker && round > 1) roundPrompts else null,
                ))

                isFirstSpeaker = false
                total += result

                eventBridge.onDeliberationSpeakerDone(run, task, participantId, round, result.summary)

                // Incrementally persist deliberation history after each speaker completes
                store?.saveDeliberationHistory(run.id, task.id, history.toList())
            }
        }

        // If aborted, return CANCELLED without invoking judge
        if (abortSignal()) {
            logger.info("Deliberation '{}' cancelled by abort signal before judge", task.id)
            val cancelledSnapshot = total.snapshot()
            return WorkerResult(
                SwarmTaskStatus.CANCELLED, "",
                inputTokens = cancelledSnapshot.input,
                outputTokens = cancelledSnapshot.output,
                cacheReadTokens = cancelledSnapshot.cacheRead,
                cacheWriteTokens = cancelledSnapshot.cacheWrite,
                durationMs = cancelledSnapshot.duration,
                error = "Cancelled by abort signal",
                deliberationHistory = history
            )
        }

        // --- Verdict: Judge renders final verdict ---
        eventBridge.onDeliberationJudging(run, task)
        logger.info("Deliberation '{}' — Judge rendering verdict after {} entries", task.id, history.size)

        val verdictPrompt = buildVerdictPrompt(history, allParticipants, judgeSpec.id, judgeSpec.role)
        val finalVerdictPrompt = if (run.language.isNotBlank()) {
            verdictPrompt + buildLanguageSegment(run.language)
        } else {
            verdictPrompt
        }
        val verdict = workerExecutor.executeWorker(judgeSpec, finalVerdictPrompt, run, task, runContext, abortSignal)

        total += verdict

        eventBridge.onDeliberationCompleted(run, task, verdict.summary)

        val finalSnapshot = total.snapshot()
        return WorkerResult(
            status = SwarmTaskStatus.COMPLETED,
            summary = verdict.summary,
            iterations = history.size + 1,
            inputTokens = finalSnapshot.input,
            outputTokens = finalSnapshot.output,
            cacheReadTokens = finalSnapshot.cacheRead,
            cacheWriteTokens = finalSnapshot.cacheWrite,
            durationMs = finalSnapshot.duration,
            deliberationHistory = history,
            verdictPrompt = finalVerdictPrompt,
            verdictResponse = verdict.summary,
        )
    }

    // --- Judge Orchestrator: Opening prompt generation ---

    private fun buildOpeningGenerationPrompt(
        deliberationContext: String,
        participantProfiles: String,
    ): String = buildString {
        if (deliberationContext.isNotBlank()) {
            appendLine("## Deliberation Context")
            appendLine(deliberationContext)
            appendLine()
        }
        appendLine("## Participants")
        appendLine(participantProfiles)
        appendLine()
        appendLine("## Instructions")
        appendLine("Generate an opening prompt that:")
        appendLine("1. Clearly frames the deliberation topic/context")
        appendLine("2. Asks each participant to provide their initial analysis from their area of expertise")
        appendLine("3. Sets expectations for constructive, focused discussion")
        appendLine()
        appendLine("Output ONLY the prompt text. No explanation, no preamble, no JSON wrapper.")
    }

    // --- Judge Orchestrator: Round prompt generation ---

    /**
     * Ask the Judge to generate personalized prompts for each participant.
     * Returns null if the Judge decides the deliberation has converged.
     */
    private suspend fun generateRoundPrompts(
        judgeSpec: SwarmAgentSpec,
        run: SwarmRun,
        task: SwarmTask,
        runContext: RunContext,
        abortSignal: () -> Boolean,
        total: TokenCounters,
        history: List<DeliberationEntry>,
        round: Int,
        participantProfiles: String,
        allParticipants: List<Pair<String, String>>
    ): Map<String, String>? {
        val prompt = buildString {
            appendLine("## Deliberation Orchestration — Round $round")
            appendLine()
            appendLine("### Deliberation History")
            appendLine(formatDeliberationHistory(history))
            appendLine()
            appendLine("### Participants")
            appendLine(participantProfiles)
            appendLine()
            appendLine("### Your Decision")
            appendLine("First, briefly assess whether the deliberation has reached consensus or a satisfactory conclusion.")
            appendLine("Review the deliberation history in the XML block above to make your assessment.")
            appendLine()
            appendLine("If **converged** (consensus reached): respond with exactly:")
            appendLine("```json")
            appendLine("{\"converged\": true, \"reason\": \"brief explanation\"}")
            appendLine("```")
            appendLine()
            appendLine("If **NOT converged** (more discussion needed): generate a personalized prompt for EACH participant.")
            appendLine("Each prompt should reference relevant prior arguments and direct the participant to address unresolved points.")
            appendLine("Each prompt should remind the participant which entries in the history are their own (by agent ID), so they can distinguish their own statements from others'.")
            appendLine()
            appendLine("Respond with JSON:")
            appendLine("```json")
            appendLine("{")
            appendLine("  \"converged\": false,")
            appendLine("  \"reason\": \"brief explanation\",")
            appendLine("  \"prompts\": [")
            for ((id, _) in allParticipants) {
                appendLine("    { \"participantId\": \"$id\", \"prompt\": \"personalized prompt for this participant\" },")
            }
            appendLine("  ]")
            appendLine("}")
            appendLine("```")
            appendLine()
            appendLine("Output ONLY the JSON. No explanation outside the JSON.")
        }

        val result = workerExecutor.executeWorker(
            judgeSpec, prompt, run, task, runContext, abortSignal,
            outputSchemaOverride = ROUND_PROMPTS_SCHEMA,
            systemPromptOverride = ORCHESTRATOR_SYSTEM_PROMPT
        )
        total += result

        if (result.status == SwarmTaskStatus.FAILED) {
            logger.warn("Judge failed to generate round {} prompts: {}, using fallback", round, result.error)
            return buildFallbackRoundPrompts(history, round, allParticipants)
        }

        return parseRoundPromptsResponse(result.summary, allParticipants, round, history)
    }

    /**
     * Parse the Judge's JSON response for round prompt generation.
     * Returns null if converged, or a map of participantId -> prompt.
     */
    private fun parseRoundPromptsResponse(
        response: String,
        allParticipants: List<Pair<String, String>>,
        round: Int,
        history: List<DeliberationEntry>
    ): Map<String, String>? {
        try {
            // Extract JSON from possible markdown code block
            val jsonStr = extractJson(response)
            val node = objectMapper.readTree(jsonStr)

            // Check convergence
            val converged = node.get("converged")?.asBoolean() ?: false
            if (converged) {
                val reason = node.get("reason")?.asString() ?: ""
                logger.info("Judge signaled convergence at round {}: {}", round, reason)
                return null
            }

            // Extract per-participant prompts from array format
            val prompts = mutableMapOf<String, String>()
            val promptsArray = node.get("prompts")
            if (promptsArray != null && promptsArray.isArray) {
                for (entry in promptsArray) {
                    val participantId = entry.get("participantId")?.asString() ?: continue
                    val participantPrompt = entry.get("prompt")?.asString() ?: continue
                    prompts[participantId] = participantPrompt
                }
            }

            // Fill in fallback for any missing participants
            for ((id, _) in allParticipants) {
                if (id !in prompts || prompts[id]?.isBlank() != false) {
                    logger.warn("Judge did not generate prompt for participant '{}', using fallback", id)
                    prompts[id] = buildFallbackPrompt(history = history, round = round, participantId = id)
                }
            }
            return prompts
        } catch (e: Exception) {
            logger.warn("Failed to parse Judge round response: {}, using fallback", e.message)
            return buildFallbackRoundPrompts(history, round, allParticipants)
        }
    }

    private fun extractJson(text: String): String = extractJsonFromCodeBlock(text)

    private fun buildFallbackRoundPrompts(
        history: List<DeliberationEntry>,
        round: Int,
        allParticipants: List<Pair<String, String>>
    ): Map<String, String> {
        return allParticipants.associate { (id, _) ->
            id to buildFallbackPrompt(history, round, id)
        }
    }

    private fun buildFallbackPrompt(
        history: List<DeliberationEntry>,
        round: Int,
        participantId: String
    ): String = buildString {
        appendLine("The deliberation history below is provided in XML tags for clarity.")
        appendLine(formatDeliberationHistory(history))
        appendLine()
        appendLine("You are **$participantId**. The entries above labeled with your agent ID are your own prior statements.")
        appendLine()
        appendLine("Round $round: Please continue the discussion. Engage with other participants' arguments")
        appendLine("and provide your perspective based on your expertise.")
    }

    // --- Verdict ---

    private fun buildVerdictPrompt(
        history: List<DeliberationEntry>,
        allParticipants: List<Pair<String, String>>,
        judgeId: String,
        judgeRole: String,
    ): String = buildString {
        appendLine("## Final Verdict")
        appendLine()
        appendLine("You are **$judgeId** ($judgeRole), the judge in this deliberation.")
        appendLine()
        appendLine("You are reviewing a deliberation between:")
        for ((id, role) in allParticipants) {
            appendLine("- $id ($role)")
        }
        appendLine()
        appendLine("The deliberation history below is provided in XML tags for clarity.")
        appendLine(formatDeliberationHistory(history))
        appendLine()
        appendLine("### Instructions")
        appendLine("Review the deliberation history provided in the XML block above. Evaluate the quality of arguments,")
        appendLine("identify areas of agreement and disagreement, and render a fair, well-reasoned verdict.")
        appendLine("Provide a comprehensive summary that captures the key conclusions and any remaining open points.")
    }

    // --- Helpers ---

    /**
     * Build participant profiles string for the Judge, including agent descriptions and system prompts.
     */
    private fun buildParticipantProfiles(
        allParticipants: List<Pair<String, String>>,
        runContext: RunContext,
        run: SwarmRun
    ): String = buildString {
        for ((id, role) in allParticipants) {
            val spec = run.agents.find { it.id == id }
            val agentDef = spec?.let { runContext.agentDefCache[it.cacheKey] }
            appendLine("- **$id** ($role)")
            val desc = agentDef?.description
            if (desc != null) {
                appendLine("  Description: $desc")
            }
            val promptTpl = agentDef?.promptTemplate
            if (promptTpl != null) {
                val truncated = if (promptTpl.length > 500)
                    promptTpl.take(500) + "... [truncated]"
                else promptTpl
                appendLine("  System Prompt: $truncated")
            }
        }
    }

    /**
     * Format deliberation history for injection into prompts.
     */
    private fun formatDeliberationHistory(history: List<DeliberationEntry>): String =
        formatDeliberationHistoryText(history)

    /**
     * Build a language instruction segment to append to prompts.
     * Uses the same format as SINGLE task system prompts for consistency.
     */
    private fun buildLanguageSegment(language: String): String =
        "\n\n---\n\n## Language\n" +
        "All responses MUST be written in $language. " +
        "Regardless of the language used in the system prompt, user messages, or conversation history, " +
        "always reply in $language."
}

/**
 * Extract JSON from a markdown code block, or return the text as-is if no code block is found.
 */
internal fun extractJsonFromCodeBlock(text: String): String {
    val codeBlockRegex = Regex("```(?:json)?\\s*\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
    val match = codeBlockRegex.find(text)
    return match?.groupValues?.get(1)?.trim() ?: text.trim()
}

/**
 * Format deliberation history entries for injection into prompts.
 */
internal fun formatDeliberationHistoryText(history: List<DeliberationEntry>): String {
    if (history.isEmpty()) return "<deliberation_history>\n(No history yet)\n</deliberation_history>"
    return buildString {
        appendLine("<deliberation_history>")
        for (entry in history) {
            appendLine("  <entry agent=\"${entry.agentId}\" round=\"${entry.round}\">")
            appendLine(entry.response)
            appendLine("  </entry>")
        }
        append("</deliberation_history>")
    }
}
