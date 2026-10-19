package com.easy.easyai.core.validation

import com.easy.easyai.common.util.SharedObjectMapper
import org.slf4j.LoggerFactory

/**
 * Extracts JSON content from LLM response text.
 * Supports both ```json code fences and bare JSON.
 *
 * Public because every path that turns model prose into a decision needs the same attempt order;
 * the ones that re-implemented it locally (memory flush, shell risk check) only sliced at the first
 * `{`, so a fenced reply or a stray brace in a string silently parsed as garbage.
 */
object JsonExtractor {

    private val logger = LoggerFactory.getLogger(JsonExtractor::class.java)

    private const val MAX_LOGGED_CANDIDATE = 200

    // The info string is never part of the payload: ```JSON, ```jsonc and a bare ``` all have to
    // yield the body. Capturing the language instead made such a fence unparseable.
    private val CODE_FENCE_PATTERN = Regex("```[^\\n`]*\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)

    /**
     * Extract JSON string from assistant text.
     * Priority:
     * 1. The first code fence whose body is a JSON object or array, in appearance order
     * 2. The entire text as JSON
     * 3. The first balanced JSON object or array block found in the text
     *
     * @return The extracted JSON string, or null if no valid JSON found
     */
    fun extract(text: String): String? {
        val fences = CODE_FENCE_PATTERN.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()

        for (candidate in fences) {
            if (isJsonObjectOrArray(candidate)) return candidate
        }
        if (fences.isNotEmpty()) {
            // Still fall through: a model that fences its explanation often answers in bare JSON
            // right outside the fence. Falling back is fine, falling back silently is not.
            logger.warn(
                "{} fenced block(s) held no parseable JSON object, falling back to bare JSON: {}",
                fences.size,
                fences.first().take(MAX_LOGGED_CANDIDATE),
            )
        }

        val trimmed = text.trim()
        if (isValidJson(trimmed)) return trimmed

        // 3. Try to find JSON object/array in text
        findJsonBlock(trimmed, '{', '}')?.let { return it }
        return findJsonBlock(trimmed, '[', ']')
    }

    /**
     * Gate on the parser the callers actually bind with: jsonsKema accepted a trailing comma that
     * Jackson rejects, so it declared a payload extractable that died one step later in readValue.
     */
    private fun isValidJson(text: String): Boolean {
        return try {
            SharedObjectMapper.instance.readTree(text)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isJsonObjectOrArray(text: String): Boolean {
        val first = text.firstOrNull() ?: return false
        return (first == '{' || first == '[') && isValidJson(text)
    }

    /**
     * Find a balanced JSON block starting with [open] and ending with [close].
     */
    private fun findJsonBlock(text: String, open: Char, close: Char): String? {
        var searchFrom = 0
        while (searchFrom < text.length) {
            val start = text.indexOf(open, searchFrom)
            if (start == -1) return null

            var depth = 0
            var inString = false
            var escaped = false

            for (i in start until text.length) {
                val c = text[i]
                when {
                    escaped -> escaped = false
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    !inString && c == open -> depth++
                    !inString && c == close -> {
                        depth--
                        if (depth == 0) {
                            val candidate = text.substring(start, i + 1)
                            if (isValidJson(candidate)) return candidate
                            break  // This bracket pair wasn't valid JSON, try next
                        }
                    }
                }
            }
            searchFrom = start + 1
        }
        return null
    }
}
