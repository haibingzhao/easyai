package com.easy.easyai.skills.command

private val NUMBERED_PLACEHOLDER = Regex("\\$(\\d+)")

/**
 * Extract placeholder hints from a command template body.
 * Recognises `$1`, `$2`, ... `$N` and `$ARGUMENTS`.
 */
fun extractHints(template: String): List<String> {
    val result = mutableListOf<String>()
    val numbered = NUMBERED_PLACEHOLDER.findAll(template)
        .map { it.value }
        .distinct()
        .sortedBy { it.drop(1).toIntOrNull() ?: Int.MAX_VALUE }
        .toList()
    result.addAll(numbered)
    if (template.contains("\$ARGUMENTS")) {
        result.add("\$ARGUMENTS")
    }
    return result
}
