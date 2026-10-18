package com.easy.easyai.core.memory.consolidation

import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskSetting
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the hop between the consolidation view and the generic scheduled row: which key `params` is
 * read through, what shape is written back, and that every call stays pinned to the consolidation type.
 */
class ScheduledSettingBackedConsolidationStoreTest {

    private val generic = mockk<ScheduledTaskSettingStore>()

    private fun store() = ScheduledSettingBackedConsolidationStore(generic)

    private fun row(
        userId: String = "alice",
        enabled: Boolean = true,
        cron: String? = "0 0 3 * * *",
        params: Map<String, Any?> = mapOf(MemoryConsolidationUserSettings.PARAM_TYPE_THRESHOLDS to mapOf("experience_lessons" to 9))
    ) = ScheduledTaskSetting(
        userId = userId,
        taskType = AsyncTaskType.MEMORY_CONSOLIDATION,
        enabled = enabled,
        cron = cron,
        params = params
    )

    @Test
    fun `a scheduled row decodes into the consolidation view`() = runBlocking {
        coEvery { generic.get("alice", AsyncTaskType.MEMORY_CONSOLIDATION) } returns row()

        val loaded = store().get("alice")!!

        assertEquals(9, loaded.typeThresholds["experience_lessons"])
        assertEquals("0 0 3 * * *", loaded.scanCron)
        assertTrue(loaded.enabled)
        assertEquals("alice", loaded.userId)
    }

    @Test
    fun `a threshold written as another number kind still decodes`() = runBlocking {
        // JSON round-trips may hand back Long, and the store only ever offers an opaque map.
        coEvery { generic.get(any(), any()) } returns row(
            params = mapOf(MemoryConsolidationUserSettings.PARAM_TYPE_THRESHOLDS to mapOf("experience_lessons" to 9L))
        )

        assertEquals(9, store().get("alice")!!.typeThresholds["experience_lessons"])
    }

    @Test
    fun `a row without the thresholds key reads as no overrides`() = runBlocking {
        coEvery { generic.get(any(), any()) } returns row(params = mapOf("somethingElse" to 1))

        assertEquals(emptyMap(), store().get("alice")!!.typeThresholds)
    }

    @Test
    fun `an absent row reads as absent`() = runBlocking {
        coEvery { generic.get("nobody", any()) } returns null

        assertNull(store().get("nobody"))
    }

    @Test
    fun `save writes thresholds under the consolidation params key`() = runBlocking {
        val saved = slot<ScheduledTaskSetting>()
        coEvery { generic.save(capture(saved)) } returns Unit

        store().save(
            MemoryConsolidationUserSettings(
                userId = "alice",
                enabled = true,
                typeThresholds = mapOf("experience_lessons" to 5),
                scanCron = "0 15 4 * * *"
            )
        )

        val written = saved.captured
        assertEquals(AsyncTaskType.MEMORY_CONSOLIDATION, written.taskType)
        assertEquals("alice", written.userId)
        assertEquals("0 15 4 * * *", written.cron)
        assertTrue(written.enabled)
        assertEquals(
            mapOf("experience_lessons" to 5),
            written.params[MemoryConsolidationUserSettings.PARAM_TYPE_THRESHOLDS]
        )
    }

    @Test
    fun `a view with no thresholds stores an empty params map`() = runBlocking {
        val saved = slot<ScheduledTaskSetting>()
        coEvery { generic.save(capture(saved)) } returns Unit

        store().save(MemoryConsolidationUserSettings(userId = "alice", enabled = false))

        assertEquals(emptyMap(), saved.captured.params)
    }

    @Test
    fun `a group-level owner set survives the row and keeps its order`() = runBlocking {
        val saved = slot<ScheduledTaskSetting>()
        coEvery { generic.save(capture(saved)) } returns Unit

        store().save(
            MemoryConsolidationUserSettings(
                userId = "alice",
                enabled = true,
                owners = listOf("alice", "group-1")
            )
        )
        val written = saved.captured.params[MemoryConsolidationUserSettings.PARAM_OWNERS]

        assertEquals(listOf("alice", "group-1"), written, "resolution priority is the stored order")
        assertEquals(
            listOf("alice", "group-1"),
            MemoryConsolidationUserSettings.fromScheduled(saved.captured).owners
        )
    }

    @Test
    fun `a solo owner stores no key and still decodes as itself`() = runBlocking {
        val saved = slot<ScheduledTaskSetting>()
        coEvery { generic.save(capture(saved)) } returns Unit

        store().save(MemoryConsolidationUserSettings(userId = "alice", enabled = true))

        assertFalse(saved.captured.params.containsKey(MemoryConsolidationUserSettings.PARAM_OWNERS))
        assertEquals(
            listOf("alice"),
            MemoryConsolidationUserSettings.fromScheduled(saved.captured).owners
        )
    }

    @Test
    fun `round-tripping a view through a row loses nothing`() = runBlocking {
        val original = MemoryConsolidationUserSettings(
            userId = "alice",
            enabled = true,
            typeThresholds = mapOf("experience_lessons" to 12, "user_profile" to 30),
            scanCron = "0 */30 * * * *"
        )

        val decoded = MemoryConsolidationUserSettings.fromScheduled(
            MemoryConsolidationUserSettings.toScheduled(original)
        )

        assertEquals(original, decoded)
    }

    @Test
    fun `list reads and deletes stay pinned to the consolidation type`() = runBlocking {
        coEvery { generic.listEnabled(AsyncTaskType.MEMORY_CONSOLIDATION) } returns listOf(row(userId = "bob"))
        coEvery { generic.delete("bob", AsyncTaskType.MEMORY_CONSOLIDATION) } returns true

        val enabled = store().listEnabled()
        val deleted = store().delete("bob")

        assertEquals(1, enabled.size)
        assertEquals("bob", enabled.single().userId)
        assertTrue(deleted)
        coVerify(exactly = 1) { generic.listEnabled(AsyncTaskType.MEMORY_CONSOLIDATION) }
        coVerify(exactly = 1) { generic.delete("bob", AsyncTaskType.MEMORY_CONSOLIDATION) }
    }
}
