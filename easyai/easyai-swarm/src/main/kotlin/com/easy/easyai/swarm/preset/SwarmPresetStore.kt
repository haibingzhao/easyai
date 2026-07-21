package com.easy.easyai.swarm.preset

import com.easy.easyai.swarm.model.SwarmPreset
import com.easy.easyai.swarm.model.SwarmRun

/**
 * Persistence interface for swarm presets.
 *
 * Implementations may use R2DBC (async), in-memory storage, or other backends.
 * The R2DBC implementation lives in the `easyai-repository` module.
 */
interface SwarmPresetStore {

    /**
     * Save a new swarm preset.
     * @param userId Owner user ID for data isolation.
     */
    suspend fun save(preset: SwarmPreset, userId: String)

    /**
     * Find a preset by its ID.
     * Only returns presets owned by [userId] or the system user.
     */
    suspend fun findById(id: String, userId: String): SwarmPreset?

    /**
     * Find a preset by name.
     * Only returns presets owned by [userId] or the system user.
     */
    suspend fun findByName(name: String, userId: String): SwarmPreset?

    /**
     * List all presets visible to [userId] (owned + system presets).
     */
    suspend fun findAll(userId: String): List<SwarmPreset>

    /**
     * Update an existing preset.
     * @param userId Owner user ID for row-level filtering.
     */
    suspend fun update(preset: SwarmPreset, userId: String)

    /**
     * Delete a preset by name.
     * Only deletes if owned by [userId] (strict match, no system fallback).
     */
    suspend fun delete(name: String, userId: String)

    /**
     * Build a SwarmRun from a preset, filling in user-provided variables.
     */
    suspend fun buildRun(presetName: String, userId: String, userVars: Map<String, String> = emptyMap()): SwarmRun {
        val preset = findByName(presetName, userId)
            ?: throw IllegalArgumentException("Swarm preset '$presetName' not found")

        // Validate required variables
        for (variable in preset.variables) {
            if (variable.required && variable.name !in userVars && variable.defaultValue == null) {
                throw IllegalArgumentException(
                    "Missing required variable '${variable.name}' for preset '$presetName'. " +
                        "Description: ${variable.description}"
                )
            }
        }

        // Merge defaults with user values
        val mergedVars = java.util.concurrent.ConcurrentHashMap<String, String>()
        for (variable in preset.variables) {
            mergedVars[variable.name] = userVars[variable.name] ?: variable.defaultValue ?: ""
        }

        val runId = "swarm-${System.currentTimeMillis()}-$presetName"

        return SwarmRun(
            id = runId,
            presetName = presetName,
            title = preset.title,
            agents = preset.agents,
            tasks = preset.tasks,
            userVars = mergedVars,
            userId = userId,
            language = preset.language,
            variableDefinitions = preset.variables
        )
    }
}
