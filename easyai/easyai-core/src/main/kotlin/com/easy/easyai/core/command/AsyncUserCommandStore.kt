package com.easy.easyai.core.command

/**
 * Async store for user-defined slash commands.
 * All operations are suspend functions.
 *
 * Implemented by R2dbcAsyncUserCommandStore in the repository module.
 */
interface AsyncUserCommandStore {
    /**
     * Save a command (insert or update).
     * Returns the persisted command with updated timestamps.
     */
    suspend fun save(command: UserCommandDefinition, userId: String = "system"): UserCommandDefinition

    /**
     * Find a command by its ID.
     * Returns null if not found or not visible to userId.
     */
    suspend fun findById(id: String, userId: String = "system"): UserCommandDefinition?

    /**
     * Find a command by name.
     * Returns null if not found or not visible to userId.
     */
    suspend fun findByName(name: String, userId: String = "system"): UserCommandDefinition?

    /**
     * List all commands visible to the given userId (user's own + system commands).
     */
    suspend fun findAll(userId: String = "system"): List<UserCommandDefinition>

    /**
     * Update an existing command.
     * Returns the updated command.
     */
    suspend fun update(command: UserCommandDefinition, userId: String = "system"): UserCommandDefinition

    /**
     * Delete a command by ID.
     * Only the owner can delete (uses strict userId filter).
     */
    suspend fun delete(id: String, userId: String = "system")
}
