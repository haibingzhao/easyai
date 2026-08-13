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

Do NOT use calc for:
- Computations that need to read files or other external data: use bash with a Python script instead
- Batch / statistical processing over large datasets (e.g., computing indicators for many symbols): use bash with pandas instead
- Anything requiring third-party libraries

Available inside the script:
- Standard operators: +, -, *, /, %, ** (power)
- java.lang.Math: Math.sqrt(), Math.pow(), Math.round(), Math.PI, Math.E, etc.
- java.time API: LocalDate, LocalDateTime, LocalTime, ZonedDateTime, Duration, Period, ChronoUnit
- Groovy features: closures, ranges, list/map operations, string interpolation

Examples:
- script: "Math.sqrt(144) + Math.pow(2, 10)"
- script: "LocalDate.of(2024, 1, 1).plusDays(30)"
- script: "ChronoUnit.DAYS.between(LocalDate.parse('2024-01-01'), LocalDate.parse('2024-12-31'))"
- script: "def total = (1..100).sum(); total * 0.15"
- script: "BigDecimal.valueOf(1234.56) * BigDecimal.valueOf(0.0825)"

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
