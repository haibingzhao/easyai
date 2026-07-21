package com.easy.easyai.skills.command

/**
 * Handler for built-in slash commands that require runtime side effects
 * (e.g., database writes, state changes) beyond simple template expansion.
 *
 * Built-in commands have the highest priority in [CommandService.resolveAndExpand]
 * and cannot be overridden by USER or SKILL commands.
 *
 * Implementations should be registered as Spring beans and will be automatically
 * discovered by the command system.
 */
interface BuiltinCommandHandler {
    /** Command name without slash prefix (e.g., "goal") */
    val name: String

    /** Description for autocomplete */
    val description: String

    /** Parameter hints for autocomplete (e.g., listOf("\$1")) */
    val hints: List<String> get() = emptyList()

    /**
     * Execute the builtin command.
     *
     * @param sessionId Current session ID
     * @param args Command arguments (everything after "/name "); empty string if no args
     * @param userId Current user ID for ownership verification
     * @return Expansion result containing the system prompt to inject, or null if cannot handle
     */
    suspend fun execute(sessionId: String, args: String, userId: String): CommandExpansion?
}
