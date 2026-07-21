package com.easy.easyai.core.validation

import com.github.erosb.jsonsKema.JsonParser

/**
 * Extracts JSON content from LLM response text.
 * Supports both ```json code fences and bare JSON.
 */
internal object JsonExtractor {

    private val CODE_FENCE_PATTERN = Regex("```(?:json)?\\s*\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)

    /**
     * Extract JSON string from assistant text.
     * Priority:
     * 1. Match ```json ... ``` code fence
     * 2. Try parsing the entire text as JSON
     * 3. Try to find a JSON object or array block in text
     *
     * @return The extracted JSON string, or null if no valid JSON found
     */
    fun extract(text: String): String? {
        // 1. Try code fence extraction
        val match = CODE_FENCE_PATTERN.find(text)
        if (match != null) {
            val candidate = match.groupValues[1].trim()
            if (isValidJson(candidate)) return candidate
        }

        // 2. Try entire text as JSON
        val trimmed = text.trim()
        if (isValidJson(trimmed)) return trimmed

        // 3. Try to find JSON object/array in text
        val jsonObject = findJsonBlock(trimmed, '{', '}')
        if (jsonObject != null && isValidJson(jsonObject)) return jsonObject

        val jsonArray = findJsonBlock(trimmed, '[', ']')
        if (jsonArray != null && isValidJson(jsonArray)) return jsonArray

        return null
    }

    private fun isValidJson(text: String): Boolean {
        return try {
            JsonParser(text).parse()
            true
        } catch (_: Exception) {
            false
        }
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
