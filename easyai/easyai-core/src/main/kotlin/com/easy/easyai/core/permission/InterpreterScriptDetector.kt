package com.easy.easyai.core.permission

/**
 * Detects commands whose executed code is not present in the command text: an
 * interpreter loading a script file (`python3 app.py`) or a module (`python3 -m pkg`).
 *
 * The AI risk check only sees the command line plus the permission rules, so for these
 * commands the model can answer nothing better than "unknown". [PermissionService] uses
 * this to short-circuit straight to ASK instead of paying for an LLM round trip.
 *
 * The rule is deliberately narrow — a false positive locks a possibly harmless command
 * into ASK forever, while a false negative only costs one LLM call.
 */
internal object InterpreterScriptDetector {

    /** Interpreters that run code from a file or module, besides the `python*` family. */
    private val INTERPRETER_NAMES = setOf(
        "node", "iojs", "deno", "bun", "ts-node", "tsx",
        "ruby", "perl", "php", "bash", "sh", "zsh", "dash", "ksh"
    )

    private val SCRIPT_FILE_SUFFIXES = setOf(
        ".py", ".pyc", ".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx",
        ".rb", ".pl", ".pm", ".php", ".sh", ".bash", ".zsh"
    )

    /** Flags that put the program source directly into the command line. */
    private val INLINE_CODE_FLAGS = setOf("-c", "-e", "--eval", "--cmd")

    /** Flags that make the interpreter load a module by name instead of a visible file. */
    private val MODULE_FLAGS = setOf("-m", "--module")

    private val ENV_ASSIGNMENT_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")

    /** True when the command runs code the model cannot see. */
    fun loadsInvisibleScript(command: String): Boolean =
        SafeCommandDetector.splitCompoundCommand(command.trim())
            .any { segmentLoadsInvisibleScript(it) }

    private fun segmentLoadsInvisibleScript(segment: String): Boolean {
        val tokens = SafeCommandDetector.splitRespectingQuotes(segment.trim())
        var index = 0
        // `MODE=prod python3 app.py` still runs a script file.
        while (index < tokens.size && ENV_ASSIGNMENT_REGEX.matches(tokens[index])) index++
        if (index >= tokens.size) return false
        if (!isInterpreter(tokens[index].substringAfterLast('/'))) return false

        val operands = tokens.drop(index + 1)
        for (token in operands) {
            // Source is visible in the command text, so the model can judge it.
            if (token in INLINE_CODE_FLAGS) return false
            if (SCRIPT_FILE_SUFFIXES.any { token.lowercase().endsWith(it) }) return true
        }
        return operands.any { it in MODULE_FLAGS }
    }

    private fun isInterpreter(name: String): Boolean =
        name.startsWith("python") || name in INTERPRETER_NAMES
}
