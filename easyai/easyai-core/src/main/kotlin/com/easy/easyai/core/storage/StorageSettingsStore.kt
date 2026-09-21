package com.easy.easyai.core.storage

/**
 * Persistence for per-user [StorageSettings] — one row per owner, mirroring the
 * `model_provider_config` user-scoped precedent.
 *
 * Interface lives in `easyai-core` (implemented by `R2dbcStorageSettingsStore` in
 * `easyai-repository`) so the storage auto-configuration can reach it without depending on
 * the repository module.
 */
interface StorageSettingsStore {

    /** The owner's stored configuration; null when the user never saved one. */
    suspend fun get(userId: String): StorageSettings?

    /** Insert or replace the owner's row; [userId] is the only key. */
    suspend fun save(settings: StorageSettings, userId: String)

    /** Drop the owner's row, returning them to the lower-priority defaults; false when absent. */
    suspend fun delete(userId: String): Boolean
}
