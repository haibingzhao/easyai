package com.easy.easyai.core.watch

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Output schemas and prompt text for watch runs.
 *
 * The schemas are the only contract the model answers against; the handler is the only writer, so a
 * run that returns prose simply fails instead of half-applying. Prompts carry the watch's own data
 * (condition, tags, previous finding) and the conflict rules; tool documentation itself is not
 * repeated here — the harness already injects every registered tool's description.
 */
object WatchPrompts {

    const val MAX_CONCLUSION_CHARS = 1200
    const val MAX_SOURCES = 8

    val CHECK_INSTRUCTIONS = """
        You are a watch runner. You check one standing condition and report one conclusion. Nobody is
        watching this run live: you cannot ask a question, and a permission prompt would hang it.

        Evidence rules:
        - Use only the read-only tools you were given. Never invent a fact, a number, a price or a URL.
        - When a source you need is blocked, unavailable or would require an interactive tool, say so in
          the conclusion and return verdict "unknown" — do not guess and do not retry the same call.
        - A label is not evidence: read the actual content before judging it.
        - The condition is a standing instruction written earlier. Interpret it as written; do not
          broaden it into a general research task.

        Output rules:
        - "conclusion" is what the user should read: the finding itself, in their language, with the
          concrete numbers or facts that support it. Stay under $MAX_CONCLUSION_CHARS characters.
        - "verdict" is the machine-readable outcome: "met" when the condition is now true, "not_met"
          when it is false, "unknown" when the evidence was not available, "note" for information that
          does not fit a yes/no judgement.
        - "tags" may only use keys from the watch's tag universe. Tags describe what this finding is
          (for example which threshold was crossed); a verdict does not need a tag.
        - "sources" lists the pages or records the conclusion rests on, each with a short label and an
          http(s) URL. Omit a source you cannot link.
        - Answer with the required JSON object only. No prose around it.
    """.trimIndent()

    val EXPIRY_REVIEW_INSTRUCTIONS = """
        You are a watch runner reviewing a watch whose lifetime has run out. Decide whether the
        standing intent is still worth keeping, and nothing else. Nobody is watching this run live:
        you cannot ask a question, and a permission prompt would hang it.

        Rules:
        - "complete": the condition was met, the goal was reached, or the thing being watched is over.
        - "renew": there is a concrete future moment worth checking (a date the user named, a release,
          a deadline, a recurring obligation). A recurring watch renews with "new_until"; a one-shot
          watch moves its single next run with "reschedule_at".
        - "expire": the condition no longer makes sense, the evidence is gone, or there is no basis for
          another date. Expiring is the honest answer when you are unsure — the user can renew it.
        - Never renew merely to keep watching. A renewal needs a reason you can state and a date.
        - Use the read-only tools you were given if a quick look would change the decision; do not
          re-run the whole investigation.
        - "conclusion" is one sentence the user will read. "reason" explains the decision.
        - Answer with the required JSON object only. No prose around it.
    """.trimIndent()

    /** JSON Schema for one check finding; the tag enum is the watch's own universe. */
    fun findingSchema(tags: List<WatchTag>): String {
        // An empty `enum` is invalid in several structured-output implementations, so a watch with
        // no tag universe gets a schema that only admits an empty tags array.
        val tagsSchema = if (tags.isEmpty()) {
            """{"type": "array", "maxItems": 0, "items": {"type": "string"}}"""
        } else {
            val tagEnum = tags.joinToString(prefix = "[", postfix = "]") { "\"${escape(it.key)}\"" }
            """{"type": "array", "maxItems": 8, "items": {"type": "string", "enum": $tagEnum}}"""
        }
        return """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["conclusion", "verdict", "tags"],
              "properties": {
                "conclusion": {"type": "string", "minLength": 1, "maxLength": $MAX_CONCLUSION_CHARS},
                "verdict": {"type": "string", "enum": ["met", "not_met", "unknown", "note"]},
                "tags": $tagsSchema,
                "sources": {
                  "type": "array",
                  "maxItems": $MAX_SOURCES,
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["label", "url"],
                    "properties": {
                      "label": {"type": "string", "minLength": 1, "maxLength": 120},
                      "url": {"type": "string", "minLength": 1, "maxLength": 600}
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }

    val EXPIRY_REVIEW_SCHEMA = """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["decision", "reason", "conclusion"],
          "properties": {
            "decision": {"type": "string", "enum": ["renew", "complete", "expire"]},
            "reason": {"type": "string", "minLength": 1, "maxLength": 1000},
            "conclusion": {"type": "string", "minLength": 1, "maxLength": 600},
            "new_until": {"type": "string", "maxLength": 40},
            "reschedule_at": {"type": "string", "maxLength": 60}
          }
        }
    """.trimIndent()

    fun checkPrompt(
        watch: Watch,
        now: Instant,
        scheduledFor: Instant,
        lateByMs: Long,
        toolNames: List<String>
    ): String = buildString {
        appendLine("Watch check.")
        appendLine("Title: ${watch.title}")
        appendLine("Condition: ${watch.condition}")
        appendLine("Time zone: ${watch.schedule.zone.id}")
        appendLine("Scheduled for: ${format(scheduledFor, watch.schedule.zone)}")
        appendLine("Now: ${format(now, watch.schedule.zone)}")
        if (lateByMs > 60_000) appendLine("This run is late by ${lateByMs / 60_000} minute(s).")
        appendLine("Schedule: ${describeSchedule(watch)}")
        watch.untilAt?.let { appendLine("Watch until: ${format(it, watch.schedule.zone)}") }
        if (watch.tags.isNotEmpty()) {
            appendLine()
            appendLine("Tag universe (use keys only):")
            watch.tags.forEach { tag ->
                val description = tag.description?.let { " — $it" }.orEmpty()
                appendLine("- ${tag.key}: ${tag.label}$description")
            }
        }
        watch.sourceHints?.let {
            appendLine()
            appendLine("Where to look (recorded when this watch was created):")
            appendLine(it)
        }
        appendLine()
        appendLine("Available data tools: ${toolNames.ifEmpty { listOf("(none)") }.joinToString(", ")}")
        watch.lastFinding?.let { previous ->
            appendLine()
            appendLine("Previous finding (version ${previous.definitionVersion}):")
            appendLine("- verdict: ${previous.verdict.name.lowercase()}")
            appendLine("- tags: ${previous.tags.joinToString(", ") { it.key }.ifEmpty { "(none)" }}")
            appendLine("- conclusion: ${previous.conclusion.take(600)}")
        }
        appendLine()
        appendLine("Report this run's finding as the required JSON object.")
    }

    fun expiryReviewPrompt(
        watch: Watch,
        now: Instant,
        recentFindings: List<WatchFinding>,
        extensionCount: Int,
        maxAutoExtensions: Int,
        horizonDays: Long
    ): String = buildString {
        appendLine("Expiry review.")
        appendLine("Title: ${watch.title}")
        appendLine("Condition: ${watch.condition}")
        appendLine("Mode: ${watch.mode.name.lowercase()}")
        appendLine("Time zone: ${watch.schedule.zone.id}")
        appendLine("Schedule: ${describeSchedule(watch)}")
        watch.untilAt?.let { appendLine("Until: ${format(it, watch.schedule.zone)}") }
        appendLine("Now: ${format(now, watch.schedule.zone)}")
        appendLine("Automatic renewals already used: $extensionCount of $maxAutoExtensions")
        appendLine("A renewal date must be in the future and within $horizonDays days.")
        appendLine()
        watch.statusReason?.let { appendLine("Why this review exists: $it") }
        watch.sourceHints?.let {
            appendLine()
            appendLine("Where to look:")
            appendLine(it)
        }
        if (recentFindings.isNotEmpty()) {
            appendLine()
            appendLine("Recent findings, newest first:")
            recentFindings.forEach { finding ->
                appendLine(
                    "- ${finding.verdict.name.lowercase()}" +
                        " | tags: ${finding.tags.joinToString(", ") { it.key }.ifEmpty { "(none)" }}" +
                        " | ${finding.conclusion.take(400)}"
                )
            }
        }
        appendLine()
        appendLine(
            if (extensionCount >= maxAutoExtensions) {
                "Automatic renewal is exhausted. Choose \"complete\" or \"expire\"; \"renew\" will be refused."
            } else {
                "Decide whether this watch should be renewed, completed or expired."
            }
        )
    }

    fun describeSchedule(watch: Watch): String = when (watch.schedule.kind) {
        WatchScheduleKind.AT -> "once at " + (
            watch.schedule.anchorAt?.let { format(it, watch.schedule.zone) } ?: "(unknown)"
            )
        WatchScheduleKind.DAILY -> "every day at ${watch.schedule.localTime}"
        WatchScheduleKind.WEEKLY -> "every ${watch.schedule.weekday} at ${watch.schedule.localTime}"
        WatchScheduleKind.MONTHLY -> "day ${watch.schedule.dayOfMonth} of each month at ${watch.schedule.localTime}"
    }

    private fun format(instant: Instant, zone: ZoneId): String =
        DISPLAY_FORMAT.withZone(zone).format(instant)

    private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
