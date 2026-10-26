package com.easy.easyai.tools.calc

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolCapability
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.stereotype.Component

private const val CALC_TOOL_DESCRIPTION = """Execute Groovy scripts in-memory for exact numerical, date/time and data-analysis work.

Use this tool for one-off computations instead of mental arithmetic:
- Mathematical / arithmetic formulas (e.g., percentages, weighted scores, ratios)
- Date and time calculations (e.g., date differences, adding/subtracting days, timezone conversions)
- Unit conversions
- Any computation where accuracy matters
- Analyzing a SINGLE data file up to 4MB — including a large tool result that was spilled to the
  temp dir (pass its path as filePath). This works in background runs where no shell is available.

Available inside the script:
- Standard operators: +, -, *, /, %, ** (power)
- java.lang.Math: Math.sqrt(), Math.pow(), Math.round(), Math.PI, Math.E, etc.
- java.time API: LocalDate, LocalDateTime, LocalTime, ZonedDateTime, Duration, Period, ChronoUnit
- groovy.json.JsonSlurper / JsonSlurperClassic for JSON text
- Groovy features: closures, ranges, list/map operations, string interpolation
- __data__: List<String> of file lines (only when filePath is provided); join it before parsing a
  single-line file

Parameters:
- script (required): Groovy script to execute
- filePath (optional): Absolute path to a data file. The host reads it and injects its lines as
  `__data__`. Regular file only (no symlinks), max 4MB, one file per call. An unattended run may
  only read tool-output spill files or files inside the bound project.

Examples:
- script: "Math.sqrt(144) + Math.pow(2, 10)"
- script: "ChronoUnit.DAYS.between(LocalDate.parse('2024-01-01'), LocalDate.parse('2024-12-31'))"
- script: "BigDecimal.valueOf(1234.56) * BigDecimal.valueOf(0.0825)"
- script: "def rows = new groovy.json.JsonSlurper().parseText(__data__.join('\n')); rows.sum { it.close }",
  filePath: "/tmp/easyai-tool-output/call_1_ab12cd34.txt"
- script: "__data__.drop(1).size()", filePath: "/path/to/data.csv"

The script executes purely in memory; the sandbox rejects file, network, process, reflection and
thread operations. Output is println text when present, otherwise the last expression value."""

/** Static guidance for on-demand time access via the calc tool (cache-stable). */
private const val TIME_ACCESS_SEGMENT = """
## Current Time

The current date and time is NOT included in this prompt to keep it stable for caching.
When you need the current date, time, or timezone (e.g. to report today's date, reason about deadlines, or compute time differences), use the `calc` tool with a script such as:
ZonedDateTime.now().toString()

This returns the current timestamp with the system's local timezone, e.g. 2026-08-09T10:30:45+08:00[Asia/Shanghai].
"""

/**
 * Builder for [ScriptCalcTool].
 *
 * Registered as a Spring component so it is automatically discovered by
 * [com.easy.easyai.tools.SpringToolFactory] via the existing `@ComponentScan`.
 */
@Component
class ScriptCalcToolBuilder : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "calc",
        description = CALC_TOOL_DESCRIPTION,
        permissionCategory = "calc",
        isDefaultTool = true,
        tracksFileChanges = false,
        patternKeys = emptyList(),
        capabilities = setOf(ToolCapability.READ_ONLY),
        systemPromptSegment = TIME_ACCESS_SEGMENT.trimIndent(),
        promptSegmentOrder = 10
    )

    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.calc", "*", PermissionAction.ALLOW)
    )

    override suspend fun build(context: AgentContext, agentService: AgentService): ToolDefinition {
        return ScriptCalcTool(metadata)
    }
}
