package com.easy.easyai.autoconfigure.core

import com.easy.easyai.skills.SkillRefreshService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test

/**
 * Tests for [SkillIndexStartupRunner] — which is only the wiring between `ApplicationReadyEvent` and
 * [SkillRefreshService.reconcileAllOwners]. What that pass does is covered by `SkillRefreshServiceTest`;
 * duplicating it here would just mean two places to update when the chain changes.
 *
 * The one behaviour worth pinning is that nothing escapes into application startup: an unreachable
 * database must not keep the already-ready application from serving.
 */
class SkillIndexStartupRunnerTest {

    private val refreshService = mockk<SkillRefreshService>()

    /** `Unconfined` runs the launched coroutine on the caller thread, so the assertions below are meaningful. */
    private fun runner() = SkillIndexStartupRunner(refreshService, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `readiness triggers exactly one reconciliation`() {
        coEvery { refreshService.reconcileAllOwners() } returns null

        runner().onApplicationReady()

        coVerify(exactly = 1) { refreshService.reconcileAllOwners() }
    }

    @Test
    fun `a pass that cannot be completed is swallowed`() {
        coEvery { refreshService.reconcileAllOwners() } throws IllegalStateException("database is down")

        runner().onApplicationReady()

        coVerify(exactly = 1) { refreshService.reconcileAllOwners() }
    }
}
