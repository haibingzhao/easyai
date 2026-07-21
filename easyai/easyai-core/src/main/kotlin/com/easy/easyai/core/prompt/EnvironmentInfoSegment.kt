package com.easy.easyai.core.prompt

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Generates environment info segment for system prompt.
 * Includes OS, current working directory, and current date/time
 * so the LLM knows where and when it is operating.
 */
object EnvironmentInfoSegment {

    private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

    /**
     * Generate environment info segment.
     * @param cwd current working directory path, or null if unknown
     * @param os operating system name, or null if unknown
     * @return environment info string, may be blank if no info available
     */
    fun generate(cwd: String?, os: String?): String {
        val lines = mutableListOf<String>()

        if (!os.isNullOrBlank()) {
            lines.add("- OS: $os")
        }
        if (!cwd.isNullOrBlank()) {
            lines.add("- Current working directory: `$cwd`")
        }
        lines.add("- Current date and time: ${ZonedDateTime.now().format(DATE_TIME_FORMATTER)}")

        return buildString {
            appendLine("## Environment")
            lines.forEach { appendLine(it) }
        }.trimEnd()
    }
}
