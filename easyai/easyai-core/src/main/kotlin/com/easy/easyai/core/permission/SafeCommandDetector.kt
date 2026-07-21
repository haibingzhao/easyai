package com.easy.easyai.core.permission

import java.nio.file.Path

/**
 * Classifies shell commands by safety level.
 * Reference: KiloCode readOnlyBash design.
 */
internal object SafeCommandDetector {

    enum class CommandSafety {
        /** Read-only command that does not modify the filesystem. */
        SAFE_READ,
        /** Read-write command that modifies the filesystem but is acceptable within project scope. */
        SAFE_WRITE,
        /** Unsafe command that requires explicit user approval. */
        UNSAFE
    }

    // Safe read-only commands (do not modify the filesystem)
    val READ_ONLY_COMMANDS = setOf(
        "cat", "head", "tail", "less", "ls", "tree", "pwd", "echo",
        "wc", "which", "type", "file", "diff", "du", "df", "date",
        "uname", "whoami", "printenv", "man", "grep", "rg", "ag",
        "sort", "uniq", "cut", "tr", "jq", "stat", "readlink",
        "basename", "dirname", "realpath", "find", "fd", "cd"
    )

    // Safe read-write commands (modify filesystem but acceptable in project scope, no rm)
    val READ_WRITE_COMMANDS = setOf(
        "touch", "mkdir", "cp", "mv", "tsc",
        "tar", "unzip", "gzip", "gunzip", "zip",
        "mvn", "gradle", "make", "cmake",
        "npm", "npx", "yarn", "pnpm"
    )

    // Git read-only subcommands (first-level only, no parameterized forms like "tag -l")
    val GIT_READ_SUBCOMMANDS = setOf(
        "log", "show", "diff", "status", "blame", "rev-parse",
        "rev-list", "ls-files", "ls-tree", "ls-remote", "shortlog",
        "describe", "cat-file", "name-rev", "stash", "tag",
        "branch", "remote", "config"
    )

    // Git write subcommands
    val GIT_WRITE_SUBCOMMANDS = setOf(
        "commit", "push", "pull", "merge", "rebase", "reset",
        "checkout", "switch", "cherry-pick",
        "am", "apply", "clean", "add", "clone",
        "init", "worktree", "submodule", "revert", "bisect",
        "filter-branch", "fetch", "restore"
    )

    // Dangerous patterns — if present, the command is always UNSAFE
    // Note: &&, ||, and | are handled by compound command splitting, not listed here
    private val DANGEROUS_PATTERNS = listOf(
        ">", ">>", ";", "&", "$(", "`", "<("
    )

    /**
     * Classify a shell command as SAFE_READ, SAFE_WRITE, or UNSAFE.
     */
    fun classify(command: String): CommandSafety {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return CommandSafety.UNSAFE

        // 1. Split compound commands by && and || (respecting quotes)
        val subCommands = splitCompoundCommand(trimmed)
        if (subCommands.size > 1) {
            var strictest = CommandSafety.SAFE_READ
            for (sub in subCommands) {
                val safety = classify(sub)
                if (safety.ordinal > strictest.ordinal) {
                    strictest = safety
                }
                if (strictest == CommandSafety.UNSAFE) return CommandSafety.UNSAFE
            }
            return strictest
        }

        // 2. Check for dangerous patterns (single command)
        for (pattern in DANGEROUS_PATTERNS) {
            if (containsOutsideQuotes(trimmed, pattern)) {
                return CommandSafety.UNSAFE
            }
        }

        val tokens = splitRespectingQuotes(trimmed)
        if (tokens.isEmpty()) return CommandSafety.UNSAFE

        val baseCommand = tokens.first()

        // 3. gh CLI is always UNSAFE (needs separate authorization)
        if (baseCommand == "gh") return CommandSafety.UNSAFE

        // 4. Git subcommand-level detection
        if (baseCommand == "git" && tokens.size > 1) {
            val subCommand = tokens[1]
            if (subCommand in GIT_READ_SUBCOMMANDS) return CommandSafety.SAFE_READ
            if (subCommand in GIT_WRITE_SUBCOMMANDS) return CommandSafety.SAFE_WRITE
            return CommandSafety.UNSAFE
        }

        // 5. Check read-only commands
        if (baseCommand in READ_ONLY_COMMANDS) {
            // Special case: sort -o writes to a file
            if (baseCommand == "sort" && tokens.any { it == "-o" }) {
                return CommandSafety.SAFE_WRITE
            }
            return CommandSafety.SAFE_READ
        }

        // 6. Check read-write commands
        if (baseCommand in READ_WRITE_COMMANDS) return CommandSafety.SAFE_WRITE

        // 7. Everything else is UNSAFE
        return CommandSafety.UNSAFE
    }

    /**
     * Extract file path arguments from a command string.
     * Handles ~, ${HOME}, $HOME expansion and relative path resolution.
     */
    fun extractPaths(command: String, projectPath: Path): List<Path> {
        val tokens = splitRespectingQuotes(command.trim())
        if (tokens.size <= 1) return emptyList()

        val paths = mutableListOf<Path>()
        // Skip the first token (the command itself)
        for (token in tokens.drop(1)) {
            // Skip flags/options
            if (token.startsWith("-")) continue

            // Check if the token looks like a path (contains / or starts with ~ or .)
            if (token.contains("/") || token.startsWith("~") || token.startsWith(".")) {
                val normalized = normalizePath(token, projectPath)
                paths.add(normalized)
            }
        }
        return paths
    }

    /**
     * Normalize a path string, expanding ~ and $HOME, resolving relative paths.
     */
    fun normalizePath(pathStr: String, projectPath: Path): Path {
        var normalized = pathStr
        // Expand ~
        if (normalized.startsWith("~/") || normalized == "~") {
            normalized = System.getProperty("user.home") + normalized.substring(1)
        }
        // Expand ${HOME} and $HOME
        val home = System.getenv("HOME") ?: System.getProperty("user.home")
        normalized = normalized.replace(Regex("\\$\\{HOME}"), home)
        normalized = normalized.replace(Regex("\\\$HOME(?=/|$)"), home)

        // Resolve to absolute path
        val resolved = Path.of(normalized).takeIf { it.isAbsolute }
            ?: projectPath.resolve(normalized).normalize()
        return resolved.normalize()
    }

    /**
     * Split a command string into tokens, respecting single and double quotes.
     */
    internal fun splitRespectingQuotes(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var i = 0

        while (i < command.length) {
            val ch = command[i]
            when {
                ch == '\'' && !inDoubleQuote -> {
                    inSingleQuote = !inSingleQuote
                    current.append(ch)
                }
                ch == '"' && !inSingleQuote -> {
                    inDoubleQuote = !inDoubleQuote
                    current.append(ch)
                }
                ch == ' ' && !inSingleQuote && !inDoubleQuote -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
            i++
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        return tokens
    }

    /**
     * Split a compound command by &&, ||, and | operators, respecting quotes.
     * Returns a list of sub-commands. Single commands return a list of size 1.
     */
    internal fun splitCompoundCommand(command: String): List<String> {
        val parts = mutableListOf<String>()
        var inSingleQuote = false
        var inDoubleQuote = false
        val current = StringBuilder()
        var i = 0

        while (i < command.length) {
            val ch = command[i]

            // Track quote state
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                current.append(ch)
                i++
                continue
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                current.append(ch)
                i++
                continue
            }

            // Inside quotes: just append
            if (inSingleQuote || inDoubleQuote) {
                current.append(ch)
                i++
                continue
            }

            // Check && (must check before single &)
            if (ch == '&' && i + 1 < command.length && command[i + 1] == '&') {
                val part = current.toString().trim()
                if (part.isNotEmpty()) parts.add(part)
                current.clear()
                i += 2
                continue
            }

            // Check ||
            if (ch == '|' && i + 1 < command.length && command[i + 1] == '|') {
                val part = current.toString().trim()
                if (part.isNotEmpty()) parts.add(part)
                current.clear()
                i += 2
                continue
            }

            // Check single | (pipe)
            if (ch == '|') {
                val part = current.toString().trim()
                if (part.isNotEmpty()) parts.add(part)
                current.clear()
                i += 1
                continue
            }

            current.append(ch)
            i++
        }

        val last = current.toString().trim()
        if (last.isNotEmpty()) parts.add(last)

        return parts
    }

    /**
     * Check if a pattern exists in the command outside of quoted sections.
     */
    private fun containsOutsideQuotes(command: String, pattern: String): Boolean {
        // Rebuild the command without quoted sections and check for the pattern
        // Simple approach: check each unquoted token and the gaps between them
        var inQuote = false
        var quoteChar = ' '
        var i = 0
        while (i < command.length) {
            val ch = command[i]
            if (!inQuote && (ch == '\'' || ch == '"')) {
                inQuote = true
                quoteChar = ch
                i++
                continue
            }
            if (inQuote && ch == quoteChar) {
                inQuote = false
                i++
                continue
            }
            if (!inQuote && command.startsWith(pattern, i)) {
                return true
            }
            i++
        }
        return false
    }
}
