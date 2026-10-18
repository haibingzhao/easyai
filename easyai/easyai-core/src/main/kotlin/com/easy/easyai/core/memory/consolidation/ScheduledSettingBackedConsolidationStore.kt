package com.easy.easyai.core.memory.consolidation

import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskSettingStore

/**
 * [MemoryConsolidationSettingsStore] backed by the generic [ScheduledTaskSettingStore], so
 * consolidation keeps its own typed view ([MemoryConsolidationUserSettings]) while its preferences
 * live in the same one-row-per-`(user, type)` shape every schedulable type shares.
 *
 * The REST surface and the Memories page are unchanged — only the storage hop became generic. This is
 * the piece that lets a new schedulable type skip adding a table of its own.
 */
class ScheduledSettingBackedConsolidationStore(
    private val store: ScheduledTaskSettingStore
) : MemoryConsolidationSettingsStore {

    private companion object {
        val TYPE: AsyncTaskType = AsyncTaskType.MEMORY_CONSOLIDATION
    }

    override suspend fun get(userId: String): MemoryConsolidationUserSettings? =
        store.get(userId, TYPE)?.let { MemoryConsolidationUserSettings.fromScheduled(it) }

    override suspend fun save(settings: MemoryConsolidationUserSettings) {
        store.save(MemoryConsolidationUserSettings.toScheduled(settings))
    }

    override suspend fun delete(userId: String): Boolean = store.delete(userId, TYPE)

    override suspend fun listEnabled(): List<MemoryConsolidationUserSettings> =
        store.listEnabled(TYPE).map { MemoryConsolidationUserSettings.fromScheduled(it) }
}
