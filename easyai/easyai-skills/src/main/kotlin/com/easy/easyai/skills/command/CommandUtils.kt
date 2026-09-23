package com.easy.easyai.skills.command

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class CommandReferenceException(message: String) : IllegalArgumentException(message)

data class ParsedCommand(val name: String, val arguments: String, val source: String? = null)

object CommandUtils {
    private const val NAME = "[a-zA-Z_][a-zA-Z0-9_:-]*"
    private val skillPrefix = Regex("^\\[/($NAME)]\\(skill:([^\\s()]+)\\)(?:\\s+|$)([\\s\\S]*)$")
    private val plainPrefix = Regex("^/($NAME)(?:\\s+|$)([\\s\\S]*)$")
    private val goalPrefix = Regex("^/goal(?=[\\u3400-\\u9fff])([\\s\\S]*)$")

    @JvmStatic
    fun parse(message: String): ParsedCommand? {
        val text = message.replace("\u200B", "")
        val skill = skillPrefix.matchEntire(text)
        if (skill != null) {
            val source = try {
                URLDecoder.decode(skill.groupValues[2].replace("+", "%2B"), StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                throw CommandReferenceException("Invalid Skill reference encoding")
            }
            val path = try { Path.of(source) } catch (_: Exception) {
                throw CommandReferenceException("Invalid Skill reference path")
            }
            if (!path.isAbsolute || path.fileName?.toString() != "SKILL.md" || source.any { it.code < 32 || it.code == 127 }) {
                throw CommandReferenceException("Skill reference must identify an absolute SKILL.md path")
            }
            return ParsedCommand(skill.groupValues[1], skill.groupValues[3], source)
        }
        if (text.startsWith("[/") && text.contains("skill:")) {
            throw CommandReferenceException("Malformed Skill reference")
        }
        plainPrefix.matchEntire(text)?.let { return ParsedCommand(it.groupValues[1], it.groupValues[2]) }
        goalPrefix.matchEntire(text)?.let { return ParsedCommand("goal", it.groupValues[1]) }
        return null
    }

    @JvmStatic
    fun skillReference(name: String, source: String): String {
        val encoded = URLEncoder.encode(source, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A")
        return "[/$name](skill:$encoded)"
    }
}

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
    if (template.contains($$"$ARGUMENTS")) {
        result.add($$"$ARGUMENTS")
    }
    return result
}
