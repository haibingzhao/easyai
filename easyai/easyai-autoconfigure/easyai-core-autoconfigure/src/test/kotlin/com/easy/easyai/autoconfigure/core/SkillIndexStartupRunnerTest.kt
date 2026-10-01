package com.easy.easyai.autoconfigure.core

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.skills.SkillRefreshService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [SkillIndexStartupRunner] — which is only the wiring between `ApplicationReadyEvent`,
 * the catalog's owner list and [SkillRefreshService.ensureSynced]. What a sync pass does is covered
 * by `SkillRefreshServiceTest`; duplicating it here would mean two places to update.
 *
 * The behaviours worth pinning are that startup never blocks or breaks the application (an
 * unreachable database must stay a warning) and that the shared `system` layer is swept even when
 * the catalog holds no rows at all.
 */
class SkillIndexStartupRunnerTest {

    private val refreshService = mockk<SkillRefreshService>()
    private val catalog = mockk<AsyncSkillCatalogStore>()

    /** `Unconfined` runs the launched coroutine on the caller thread, so the assertions below are meaningful. */
    private fun runner(store: AsyncSkillCatalogStore?) =
        SkillIndexStartupRunner(refreshService, store, CoroutineScope(Dispatchers.Unconfined))

    @Nested
    inner class `the startup pass` {

        @Test
        fun `every catalog owner gets exactly one sync`() {
            coEvery { catalog.listDistinctUserIds() } returns listOf("alice", "system")
            coEvery { refreshService.ensureSynced(any()) } returns Unit

            runner(catalog).onApplicationReady()

            coVerify(exactly = 1) { refreshService.ensureSynced("alice") }
            coVerify(exactly = 1) { refreshService.ensureSynced("system") }
        }

        @Test
        fun `an empty catalog still syncs the shared layer`() {
            coEvery { catalog.listDistinctUserIds() } returns emptyList()
            coEvery { refreshService.ensureSynced(any()) } returns Unit

            runner(catalog).onApplicationReady()

            coVerify(exactly = 1) { refreshService.ensureSynced("system") }
        }

        @Test
        fun `no catalog store still syncs the shared layer`() {
            coEvery { refreshService.ensureSynced(any()) } returns Unit

            runner(null).onApplicationReady()

            coVerify(exactly = 1) { refreshService.ensureSynced("system") }
        }

        @Test
        fun `an owner list that cannot be read is swallowed`() {
            coEvery { catalog.listDistinctUserIds() } throws IllegalStateException("database is down")

            runner(catalog).onApplicationReady()

            coVerify(exactly = 0) { refreshService.ensureSynced(any()) }
        }
    }
}
