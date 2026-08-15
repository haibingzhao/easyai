package com.easy.easyai.tools.calc

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.stereotype.Component

private const val CALC_TOOL_DESCRIPTION = """Execute Groovy scripts in-memory for SMALL, SELF-CONTAINED numerical and date/time calculations.

Use this tool for one-off computations where the data fits directly inside the script
(a few dozen values at most), instead of mental arithmetic:
- Mathematical / arithmetic formulas (e.g., percentages, weighted scores, ratios)
- Date and time calculations (e.g., date differences, adding/subtracting days, timezone conversions)
- Unit conversions
- Any computation where accuracy matters
- Calculations on a SINGLE file ≤ 512KB (use filePath parameter for files too large for context)

Available inside the script:
- Standard operators: +, -, *, /, %, ** (power)
- java.lang.Math: Math.sqrt(), Math.pow(), Math.round(), Math.PI, Math.E, etc.
- java.time API: LocalDate, LocalDateTime, LocalTime, ZonedDateTime, Duration, Period, ChronoUnit
- Groovy features: closures, ranges, list/map operations, string interpolation
- __data__: List<String> of file lines (only when filePath is provided)

Parameters:
- script (required): Groovy script to execute
- filePath (optional): Absolute path to a data file. The host reads it and injects content as `__data__` variable.
  File must be a regular file (no symlinks), max 512KB. Only ONE file supported. For multiple files or larger files, use bash tool with Python/JavaScript instead.

Examples:
- script: "Math.sqrt(144) + Math.pow(2, 10)"
- script: "LocalDate.of(2024, 1, 1).plusDays(30)"
- script: "ChronoUnit.DAYS.between(LocalDate.parse('2024-01-01'), LocalDate.parse('2024-12-31'))"
- script: "def total = (1..100).sum(); total * 0.15"
- script: "BigDecimal.valueOf(1234.56) * BigDecimal.valueOf(0.0825)"
- script: "__data__.drop(1).size()", filePath: "/path/to/data.csv"  // count rows excluding header

The script executes purely in memory; the sandbox rejects file, network, and process
operations at compile time.
Output is the value of the last expression evaluated."""

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
        patternKeys = emptyList()
    )

    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.calc", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition {
        return ScriptCalcTool(metadata)
    }
}
