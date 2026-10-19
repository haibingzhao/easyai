package com.easy.easyai.core.memory.consolidation

import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskSetting

/**
 * One user's runtime memory-consolidation preferences, edited from the Memories page and persisted as
 * the `memory_consolidation` row of the generic `scheduled_task_setting` table (see
 * [ScheduledSettingBackedConsolidationStore] for the hop).
 *
 * Consolidation is off unless [enabled]; enabling it is gated at the web boundary on a configured
 * MEMORY_CURATION aux model (a scheduled run has no chat-session model to fall back to). Thresholds
 * are per memory type: a slice (the user's GLOBAL memory, or each of their PROJECT slices) is
 * consolidated for a type only when that type's *active* entry count reaches its threshold.
 *
 * [sliceStates] is not a preference and nobody edits it: it is the scheduler's memory of what it has
 * already done per slice, stored in the same row so a new schedulable type still needs no table of its
 * own. See [MemoryConsolidationSliceState] for the questions it answers.
 *
 * @property enabled master runtime switch for this user (default off)
 * @property owners the caller's whole aux-resolution owner set (self + group bucket) as of the last
 *   save. The scheduled tick has no security context, so it cannot re-derive the group; without this
 *   snapshot a MEMORY_CURATION choice made on the group bucket would gate the switch open at the web
 *   boundary and then fail every run. Self is always included, so a stale extra entry can only widen
 *   resolution, never narrow it.
 * @property typeThresholds per-type "consolidate when active count reaches N", keyed by
 *   [com.easy.easyai.core.memory.MemoryType.dirName]; a type absent here uses the deployment default
 * @property scanCron optional per-user override of the deployment scan cron; null follows the default
 * @property sliceStates what the scheduler remembers per slice, keyed by the consolidation slice key
 * @property updatedAt epoch-ms of the last change
 */
data class MemoryConsolidationUserSettings(
    val userId: String,
    val enabled: Boolean = false,
    val owners: List<String> = listOf(userId),
    val typeThresholds: Map<String, Int> = emptyMap(),
    val scanCron: String? = null,
    val sliceStates: Map<String, MemoryConsolidationSliceState> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** The `params` key consolidation stores its per-type thresholds under. */
        const val PARAM_TYPE_THRESHOLDS = "typeThresholds"

        /** The `params` key consolidation stores its aux owner set under. */
        const val PARAM_OWNERS = "owners"

        /** The `params` key consolidation stores its per-slice scheduler memory under. */
        const val PARAM_SLICE_STATES = "sliceStates"

        /** Project a generic scheduled row onto this type's view; unknown keys in `params` are ignored. */
        @JvmStatic
        fun fromScheduled(setting: ScheduledTaskSetting): MemoryConsolidationUserSettings =
            MemoryConsolidationUserSettings(
                userId = setting.userId,
                enabled = setting.enabled,
                owners = ownersOf(setting.params[PARAM_OWNERS], setting.userId),
                typeThresholds = thresholdsOf(setting.params[PARAM_TYPE_THRESHOLDS]),
                scanCron = setting.cron,
                sliceStates = sliceStatesOf(setting.params[PARAM_SLICE_STATES]),
                updatedAt = setting.updatedAt
            )

        /** Fold this type's view into a generic scheduled row; empty values store no `params`. */
        @JvmStatic
        fun toScheduled(setting: MemoryConsolidationUserSettings): ScheduledTaskSetting = ScheduledTaskSetting(
            userId = setting.userId,
            taskType = AsyncTaskType.MEMORY_CONSOLIDATION,
            enabled = setting.enabled,
            cron = setting.scanCron,
            params = buildMap {
                if (setting.typeThresholds.isNotEmpty()) put(PARAM_TYPE_THRESHOLDS, setting.typeThresholds)
                // A single-owner set is what a row without this key already means, so store no noise.
                if (setting.owners.size > 1) put(PARAM_OWNERS, setting.owners)
                if (setting.sliceStates.isNotEmpty()) {
                    put(
                        PARAM_SLICE_STATES,
                        setting.sliceStates.mapValues { (_, state) ->
                            buildMap {
                                state.activeFingerprint?.let { put("fingerprint", it) }
                                state.lastRunAt?.let { put("lastRunAt", it) }
                                if (state.zeroGainStreak > 0) put("zeroGainStreak", state.zeroGainStreak)
                                state.nextEligibleAt?.let { put("nextEligibleAt", it) }
                            }
                        }
                    )
                }
            },
            updatedAt = setting.updatedAt
        )

        /**
         * Decode the owner set written back by the JSON round-trip, always with the row's own user
         * first: resolution priority is self → group, and a row saved by an older build carries no
         * `owners` at all.
         */
        private fun ownersOf(raw: Any?, userId: String): List<String> {
            val stored = (raw as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() }.orEmpty()
            return (listOf(userId) + stored).distinct()
        }

        /**
         * Decode the thresholds written back by the JSON round-trip. Numbers come back as whatever the
         * parser chose and the store hands over an opaque map, so any [Number] is accepted rather than
         * assuming `Int`.
         */
        private fun thresholdsOf(raw: Any?): Map<String, Int> {
            val entries = raw as? Map<*, *> ?: return emptyMap()
            return entries.entries.mapNotNull { (key, value) ->
                val name = key as? String ?: return@mapNotNull null
                val count = (value as? Number)?.toInt() ?: return@mapNotNull null
                name to count
            }.toMap()
        }

        /** Decode the per-slice scheduler memory, tolerating a row written before a field existed. */
        private fun sliceStatesOf(raw: Any?): Map<String, MemoryConsolidationSliceState> {
            val entries = raw as? Map<*, *> ?: return emptyMap()
            return entries.entries.mapNotNull { (key, value) ->
                val sliceKey = key as? String ?: return@mapNotNull null
                val state = value as? Map<*, *> ?: return@mapNotNull null
                sliceKey to MemoryConsolidationSliceState(
                    activeFingerprint = (state["fingerprint"] as? Number)?.toInt(),
                    lastRunAt = (state["lastRunAt"] as? Number)?.toLong(),
                    zeroGainStreak = (state["zeroGainStreak"] as? Number)?.toInt() ?: 0,
                    nextEligibleAt = (state["nextEligibleAt"] as? Number)?.toLong()
                )
            }.toMap()
        }
    }
}

/**
 * What the scheduler remembers about one slice, so a daily scan cannot ask the same question forever.
 *
 * A threshold alone does not answer "is there anything to do": merged entries stay active and keep
 * counting, so a slice that has been curated would drift back over its threshold on new memories *and*
 * on its own output. These three fields separate the two.
 *
 * @property activeFingerprint hash of the slice's active docId set **as of the end of the last run**.
 *   Equal means nothing arrived and nothing was removed since, so the same clustering question would get
 *   the same answer. Recomputing it after the run is what keeps a run's own archiving and writing from
 *   re-dirtying the slice.
 * @property lastRunAt epoch-ms of that run
 * @property zeroGainStreak consecutive runs that archived nothing. A model that finds nothing to merge
 *   leaves the active count untouched, so without a delay the scan would re-ask it at every tick,
 *   forever, at real token cost.
 * @property nextEligibleAt epoch-ms before which the scheduler must not even count this slice; null means
 *   no delay is in force. A manual run ignores it — the user asked.
 */
data class MemoryConsolidationSliceState(
    val activeFingerprint: Int? = null,
    val lastRunAt: Long? = null,
    val zeroGainStreak: Int = 0,
    val nextEligibleAt: Long? = null
)
