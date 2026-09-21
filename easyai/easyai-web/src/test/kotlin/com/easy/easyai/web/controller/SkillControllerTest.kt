package com.easy.easyai.web.controller

import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.skills.SkillCatalogService
import com.easy.easyai.skills.SkillCatalogView
import com.easy.easyai.skills.SkillPromptSource
import com.easy.easyai.skills.SkillRegistry
import com.easy.easyai.skills.SkillToggleResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [SkillController] — the HTTP surface of the skill listing and of the skill catalog.
 *
 * Two contracts matter more than the happy path: a disabled feature must answer 503 rather than
 * pretend, and the owner of every write must come from the security context. The second one is
 * asserted structurally as well as behaviourally, because a request type with no owner field cannot
 * name one.
 *
 * There is no install or publish coverage on purpose: a skill is authored on disk and indexed *into*
 * RAG for discovery, so no endpoint serves content back out of the index.
 */
class SkillControllerTest {

    private val catalogService = mockk<SkillCatalogService>(relaxed = true)
    private val registry = mockk<SkillRegistry>(relaxed = true)
    private val promptSource = mockk<SkillPromptSource>(relaxed = true)

    private fun controller(): SkillController = SkillController(
        skillRegistry = registry,
        skillCatalogService = catalogService,
        skillPromptSource = promptSource
    )

    private fun view(enabled: Boolean = true) = SkillCatalogView(
        entry = SkillCatalogEntry(
            id = "row-1",
            name = "pdf-report",
            source = SkillCatalogEntry.SOURCE_LOCAL,
            version = "1.2.3",
            checksum = "a".repeat(64),
            enabled = enabled,
            installPath = "/home/alice/.easyai/skills/pdf-report",
            userId = "alice"
        ),
        scope = SkillScope.GLOBAL,
        projectPath = null,
        installedOnDisk = true
    )

    private fun statusOf(block: () -> Any?): HttpStatusCode =
        assertFailsWith<ResponseStatusException> { block() }.statusCode

    @Nested
    inner class `a disabled feature says so` {

        private val bare = SkillController()

        @Test
        fun `the plain skill listing keeps working without any of it`() {
            assertEquals(emptyList<SkillDto>(), bare.listSkills(projectId = null).block())
        }

        @Test
        fun `the catalog listing is unavailable`() {
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, statusOf { bare.listCatalog().block() })
        }

        @Test
        fun `toggling is unavailable`() {
            val request = SkillEnabledRequest(name = "pdf-report", enabled = false)
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, statusOf { bare.setEnabled(request).block() })
        }
    }

    @Nested
    inner class `the catalog surface` {

        @Test
        fun `rows are listed with provenance and freshness`() {
            coEvery { catalogService.list(any()) } returns listOf(view(enabled = false))

            val dto = controller().listCatalog().block()!!.first()

            assertEquals("pdf-report", dto.name)
            assertEquals(SkillCatalogEntry.SOURCE_LOCAL, dto.source)
            assertEquals("1.2.3", dto.version)
            assertFalse(dto.enabled)
            assertEquals("global", dto.scope)
            assertTrue(dto.installedOnDisk)
        }

        @Test
        fun `a toggle reports whether the index caught up`() {
            coEvery {
                catalogService.setEnabled("pdf-report", any(), false)
            } returns SkillToggleResult.Applied("pdf-report", enabled = false, indexSynced = false)

            val dto = controller().setEnabled(SkillEnabledRequest(name = "pdf-report", enabled = false)).block()!!

            assertFalse(dto.enabled)
            assertFalse(dto.indexSynced, "the UI must be able to say the index is still catching up")
        }

        @Test
        fun `a toggle on a skill nobody owns is a client error`() {
            coEvery { catalogService.setEnabled(any(), any(), any()) } returns
                SkillToggleResult.Rejected("Skill 'pdf-report' is not installed for user 'alice'")

            val error = assertFailsWith<ResponseStatusException> {
                controller().setEnabled(SkillEnabledRequest(name = "pdf-report", enabled = true)).block()
            }

            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }
    }

    @Nested
    inner class `ownership comes from the session` {

        /** Signs the request as alice, which is the only way a handler may learn its owner. */
        private fun asAlice() = ReactiveSecurityContextHolder.withAuthentication(
            UsernamePasswordAuthenticationToken("alice", "", emptyList<GrantedAuthority>())
        )

        @Test
        fun `a toggle acts on the authenticated user's row`() {
            coEvery { catalogService.setEnabled(any(), any(), any()) } returns
                SkillToggleResult.Applied("pdf-report", false, true)

            controller().setEnabled(SkillEnabledRequest(name = "pdf-report", enabled = false))
                .contextWrite(asAlice()).block()

            coVerify(exactly = 1) {
                catalogService.setEnabled("pdf-report", match<SkillOwnerContext> { it.userId == "alice" }, false)
            }
        }

        @Test
        fun `no request type can name another owner`() {
            val fields = SkillEnabledRequest::class.java.declaredFields.map { it.name.lowercase() }

            assertTrue(
                fields.none { it.contains("userid") || it.contains("ownerid") },
                "SkillEnabledRequest accepts an owner: $fields"
            )
        }

        @Test
        fun `an unknown scope is refused before any catalog write`() {
            val error = assertFailsWith<ResponseStatusException> {
                controller().setEnabled(
                    SkillEnabledRequest(name = "pdf-report", enabled = true, scope = "everywhere")
                ).block()
            }

            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
            assertTrue("global" in error.reason!!, "the message has to name the valid values: ${error.reason}")
            coVerify(exactly = 0) { catalogService.setEnabled(any(), any(), any()) }
        }
    }

    @Nested
    inner class `the listing follows the prompt view` {

        @Test
        fun `with on-demand discovery the listing is not filtered by an injection view that is off`() {
            every { promptSource.fullInjectionActive } returns false
            every { registry.visibleFor(null) } returns emptyList()

            assertEquals(emptyList<SkillDto>(), controller().listSkills(projectId = null).block())
            coVerify(exactly = 0) { promptSource.skillsForPrompt(any(), any()) }
        }

        @Test
        fun `while the full list is injected a disabled skill is hidden from the ui too`() {
            every { promptSource.fullInjectionActive } returns true
            every { promptSource.skillsForPrompt(any(), any()) } returns emptyList()
            every { registry.visibleFor(null) } returns emptyList()

            assertEquals(emptyList<SkillDto>(), controller().listSkills(projectId = null).block())
        }
    }
}
