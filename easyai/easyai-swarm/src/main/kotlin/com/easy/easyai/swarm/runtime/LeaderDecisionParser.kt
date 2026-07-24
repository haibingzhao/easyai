package com.easy.easyai.swarm.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * A dynamic task created by the Leader during team coordination.
 */
internal data class DynamicTaskSpec(
    val memberId: String,
    val assignment: String,
)

/**
 * A task reassignment from a blocked member to another.
 */
internal data class Reassignment(
    val fromMemberId: String,
    val toMemberId: String,
    val reason: String,
)

/**
 * Suspend a blocked member, dispatch a helper to resolve the issue,
 * then feed the result back to the suspended member to resume.
 */
internal data class SuspendAndAssistSpec(
    val blockedMemberId: String,
    val helperMemberId: String,
    val assistTask: String,
)

/**
 * Suspend a blocked member and consult the user for a decision,
 * then feed the user's answer back to the suspended member to resume.
 */
internal data class SuspendAndConsultUserSpec(
    val blockedMemberId: String,
    val question: String,
    val options: List<String> = emptyList(),
)

/**
 * Parsed Leader decision for one coordination round.
 */
internal data class LeaderDecision(
    val analysis: String,
    val newTasks: List<DynamicTaskSpec>,
    val reassignments: List<Reassignment>,
    val isComplete: Boolean,
    val suspendAndAssist: List<SuspendAndAssistSpec> = emptyList(),
    val suspendAndConsultUser: List<SuspendAndConsultUserSpec> = emptyList(),
)

/**
 * Parses Leader LLM output into structured decisions.
 * Uses JSON-first + regex-fallback dual strategy.
 * On failure, returns a safe default (empty decision, isComplete=false).
 */
internal object LeaderDecisionParser {
    private val logger = LoggerFactory.getLogger(LeaderDecisionParser::class.java)
    private val mapper = ObjectMapper().apply {
        findAndRegisterModules()
    }

    fun parse(leaderOutput: String): LeaderDecision {
        return try {
            // Strategy 1: Try JSON parse
            parseJson(leaderOutput)
        } catch (e: Exception) {
            logger.warn("JSON parse failed, trying regex fallback: {}", e.message)
            try {
                // Strategy 2: Regex fallback
                parseRegex(leaderOutput)
            } catch (e: Exception) {
                logger.warn("Regex fallback also failed, returning empty decision: {}", e.message)
                // Strategy 3: Safe default
                LeaderDecision(
                    analysis = leaderOutput.take(500),
                    newTasks = emptyList(),
                    reassignments = emptyList(),
                    isComplete = false,
                )
            }
        }
    }

    private fun parseJson(output: String): LeaderDecision {
        // Strategy 1: Extract JSON from markdown code block
        val fromCodeBlock = extractJsonFromCodeBlock(output)
        if (fromCodeBlock != output.trim()) {
            return mapper.readValue(fromCodeBlock, LeaderDecision::class.java)
        }

        // Strategy 2: Try raw text as JSON
        try {
            return mapper.readValue(output.trim(), LeaderDecision::class.java)
        } catch (_: Exception) {
            // Fall through to strategy 3
        }

        // Strategy 3: Find first JSON object within the text (handles prose-wrapped JSON)
        val jsonStart = output.indexOf('{')
        if (jsonStart >= 0) {
            var depth = 0
            var inString = false
            var escaped = false
            for (i in jsonStart until output.length) {
                val c = output[i]
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> {
                        depth--
                        if (depth == 0) {
                            val jsonStr = output.substring(jsonStart, i + 1)
                            return mapper.readValue(jsonStr, LeaderDecision::class.java)
                        }
                    }
                }
            }
        }

        throw IllegalArgumentException("No JSON object found in Leader output")
    }

    private fun parseRegex(output: String): LeaderDecision {
        val isComplete = output.contains("COMPLETE", ignoreCase = true) ||
                         output.contains("DONE", ignoreCase = true)
        return LeaderDecision(
            analysis = output.take(500),
            newTasks = emptyList(),
            reassignments = emptyList(),
            isComplete = isComplete,
        )
    }
}
