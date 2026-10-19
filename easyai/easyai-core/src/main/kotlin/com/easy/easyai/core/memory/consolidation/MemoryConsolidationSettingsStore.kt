package com.easy.easyai.core.memory.consolidation

/**
 * Persistence for per-user [MemoryConsolidationUserSettings] — the consolidation view of one
 * `(user, memory_consolidation)` row in the shared `scheduled_task_setting` table, mirroring the
 * `aux_model_settings` user-scoped precedent.
 *
 * The interface lives in `easyai-core` (served by [ScheduledSettingBackedConsolidationStore] over the
 * generic [com.easy.easyai.core.task.ScheduledTaskSettingStore]) so the planner, the consolidation
 * agent and the web layer can reach it without depending on the repository module.
 */
interface MemoryConsolidationSettingsStore {

    /** The user's stored settings; null when they never configured consolidation (treated as off). */
    suspend fun get(userId: String): MemoryConsolidationUserSettings?

    /** Insert or replace the user's row; `userId` is the key. */
    suspend fun save(settings: MemoryConsolidationUserSettings)

    /** Drop the user's row, returning consolidation to off; false when absent. */
    suspend fun delete(userId: String): Boolean

    /**
     * Every user with consolidation enabled, for the scheduled scan. Only opted-in users are returned,
     * so materializing the (small) list once per tick is fine.
     */
    suspend fun listEnabled(): List<MemoryConsolidationUserSettings>

    /**
     * Fold one slice's post-run memory into the user's row, so the next scan can tell "nothing new since
     * the last pass" and "the last passes found nothing to merge" apart from fresh work.
     *
     * Skipped when the user has no row at all: a manual run by somebody who never opened the settings
     * page must not create one, because an absent row *is* "off, never configured". Read-modify-write,
     * so a settings edit landing inside this window wins — the next run recomputes the fingerprint
     * anyway, while a lost threshold edit would not come back on its own.
     */
    suspend fun recordSliceState(userId: String, sliceKey: String, state: MemoryConsolidationSliceState) {
        val current = get(userId) ?: return
        save(current.copy(sliceStates = current.sliceStates + (sliceKey to state)))
    }
}
