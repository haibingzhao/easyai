package com.easy.easyai.core.command

/**
 * User-defined slash command persisted in DB.
 * Each command belongs to a user (userId) and contains a prompt template.
 *
 * @param id UUID primary key.
 * @param name Command name (without leading slash). Unique per userId.
 * @param description Short description shown in autocomplete.
 * @param aliases Alternative names that also resolve to this command.
 * @param template Prompt template body. Supports $1, $2, $ARGUMENTS placeholders.
 * @param hints Placeholder hints extracted from the template (e.g. ["$1", "$ARGUMENTS"]).
 * @param userId Owner of this command. "system" = visible to all users.
 */
data class UserCommandDefinition(
    val id: String,
    val name: String,
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val template: String = "",
    val hints: List<String> = emptyList(),
    val userId: String = "system",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)
