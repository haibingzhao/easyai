package com.easy.easyai.common.util

/**
 * Indents the string by the specified number of levels.
 * @param level The number of levels to indent. This is a relative number.
 * @param tabStr The string to use for each level of indentation
 * @return The indented string
 */
fun String.indent(
  level: Int = 1,
  tabStr: String = "  ",
): String = "${tabStr.repeat(level)}$this"

/**
 * Indents each line of the string by the specified number of levels.
 * @param level The number of levels to indent. This is a relative number.
 * @param removeBlankLines If true, blank lines are removed
 * @param skipIndentFirstLine If true, the first line is not indented
 * @param tabStr The string to use for each level of indentation
 * @return The indented string
 */
fun String.indentLines(
  level: Int = 1,
  removeBlankLines: Boolean = true,
  skipIndentFirstLine: Boolean = false,
  tabStr: String = "  ",
): String =
  this
    .lines()
    .run { if (removeBlankLines) filter { it.isNotBlank() } else this }
    .mapIndexed { i, s -> i to s }
    .joinToString("\n") {
      if (skipIndentFirstLine && it.first == 0)
        it.second
      else
        it.second.indent(level, tabStr)
    }
