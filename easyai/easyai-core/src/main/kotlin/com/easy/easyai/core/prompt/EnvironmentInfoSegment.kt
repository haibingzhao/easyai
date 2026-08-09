package com.easy.easyai.core.prompt

/**
 * Generates environment info segment for system prompt.
 * Includes OS and current working directory only — intentionally static
 * so the system prompt prefix stays stable for LLM caching.
 * Current date/time is NOT injected here; agents with the calc tool get
 * on-demand access guidance appended by [PromptTemplateService].
 */
object EnvironmentInfoSegment {

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

        return buildString {
            appendLine("## Environment")
            lines.forEach { appendLine(it) }
        }.trimEnd()
    }
}
