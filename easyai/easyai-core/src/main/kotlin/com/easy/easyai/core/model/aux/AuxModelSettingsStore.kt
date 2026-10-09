package com.easy.easyai.core.model.aux

/**
 * Persistence for per-user [AuxModelSettings] — one row per `(userId, task)`, mirroring the
 * `storage_settings` user-scoped precedent.
 *
 * The interface lives in `easyai-core` (implemented by `R2dbcAuxModelSettingsStore` in
 * `easyai-repository`) so consumers such as the compaction path can reach it without depending
 * on the repository module.
 */
interface AuxModelSettingsStore {

    /** The owner's stored choice for [task]; null when the user never configured it. */
    suspend fun get(userId: String, task: AuxModelTask): AuxModelSettings?

    /** Every task the owner has configured; tasks absent from the list fall back to their default. */
    suspend fun getAll(userId: String): List<AuxModelSettings>

    /** Insert or replace the owner's row for [task]; `(userId, task)` is the key. */
    suspend fun save(userId: String, task: AuxModelTask, modelConfigId: String)

    /** Drop the owner's row for [task], returning it to the default; false when absent. */
    suspend fun delete(userId: String, task: AuxModelTask): Boolean

    // ─── Group-aware reads ───────────────────────────────────────────────────────
    // Auxiliary choices never folded in the `system` layer, so callers pass a group-owners set
    // (`SecurityUtils.currentGroupOwners()`: self + group bucket, no system). The fan-out keeps the
    // owners' order, so a personal choice wins over the group's for the same task.

    /** The effective choice for [task] across [owners]; the first owner with a row wins. */
    suspend fun get(owners: Collection<String>, task: AuxModelTask): AuxModelSettings? =
        owners.distinct().mapNotNull { get(it, task) }.firstOrNull()

    /** Every configured task across [owners]; on a taskKey collision the earlier owner wins. */
    suspend fun getAll(owners: Collection<String>): List<AuxModelSettings> =
        owners.distinct().flatMap { getAll(it) }.distinctBy { it.taskKey }
}
