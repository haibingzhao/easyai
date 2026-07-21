package com.easy.easyai.common.util


/**
 * Convert camelCase to snake_case. Also converts spaces to _
 */
fun toSnakeCase(camelCase: String): String =
    camelCase
        .replace(" ", "_")
        .split("(?<=[a-z])(?=[A-Z])".toRegex())
        .joinToString("_")
        .lowercase()

/**
 * Convert camelCase to SCREAMING_SNAKE_CASE
 */
fun toScreamingSnakeCase(camelCase: String): String =
    toSnakeCase(camelCase).uppercase()
