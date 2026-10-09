package com.easy.easyai.web.controller

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillSyncState
import com.easy.easyai.skills.ScopedSkill
import com.easy.easyai.skills.SkillAccessResolver
import com.easy.easyai.skills.SkillAddResult
import com.easy.easyai.skills.SkillCatalogService
import com.easy.easyai.skills.SkillCatalogView
import com.easy.easyai.skills.SkillConfig
import com.easy.easyai.skills.SkillInfo
import com.easy.easyai.skills.SkillRefreshService
import com.easy.easyai.skills.SkillToggleResult
import com.easy.easyai.skills.SkillUpload
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.codec.multipart.Part
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import reactor.core.publisher.Mono

/**
 * Tests for [SkillController] — the management surface of the skill subsystem.
 *
 * Three contracts matter more than the happy path:
 * 1. the owner of every read and write comes from the security context, never from a request field;
 * 2. a shared (`system`) skill is read-only for a regular user, on every mutating verb;
 * 3. an install outcome travels as status + body, because the frontend must tell "rename it" (409)
 *    apart from "that is not a skill" (400) and show the reason either way.
 */
class SkillControllerTest {

    private val catalogService = mockk<SkillCatalogService>(relaxed = true)
    private val access = mockk<SkillAccessResolver>(relaxed = true)
    private val refresh = mockk<SkillRefreshService>(relaxed = true)
    private val store = mockk<AsyncSkillCatalogStore>(relaxed = true)

    private fun controller(): SkillController = SkillController(
        skillCatalogService = catalogService,
        skillAccessResolver = access,
        skillRefreshService = refresh,
        skillCatalog = store,
        skillConfig = SkillConfig(rootDir = tempRoot.toString())
    )

    private fun entry(
        name: String = "pdf-report",
        owner: String = "alice",
        enabled: Boolean = true,
        indexed: Boolean = true
    ) = SkillCatalogEntry(
        id = "$owner-$name",
        name = name,
        version = "1.2.3",
        checksum = CHECKSUM,
        enabled = enabled,
        rootPath = tempRoot.resolve(owner).toString(),
        installPath = tempRoot.resolve(owner).resolve(name).toString(),
        objectKey = "skills/$owner/$name.zip",
        userId = owner,
        indexedChecksum = if (indexed) CHECKSUM else null,
        syncState = if (indexed && enabled) SkillSyncState.SYNCED else SkillSyncState.PENDING_INDEX
    )

    private fun installed(row: SkillCatalogEntry = entry(), onDisk: Boolean = true) = SkillCatalogView(
        entry = row,
        shared = row.userId == SkillCatalogEntry.DEFAULT_USER_ID,
        installedOnDisk = onDisk
    )

    private fun candidate(row: SkillCatalogEntry = entry()) = ScopedSkill(
        skill = SkillInfo(
            name = row.name,
            description = "PDF reports",
            location = Path.of(row.installPath, "SKILL.md"),
            content = "body",
            tags = setOf("documents")
        ),
        catalogEntry = row
    )

    private fun statusOf(block: () -> Any?): HttpStatusCode =
        assertFailsWith<ResponseStatusException> { block() }.statusCode

    @Nested
    inner class `the owner comes from the session` {

        @Test
        fun `no request type can name another owner`() {
            for (type in listOf(SkillAddRequest::class.java, SkillEnabledRequest::class.java)) {
                val fields = type.declaredFields.map { it.name.lowercase() }
                assertTrue(
                    fields.none { it.contains("userid") || it.contains("ownerid") || it == "installpath" },
                    "${type.simpleName} accepts an owner or a raw destination: $fields"
                )
            }
        }

        @Test
        fun `a listing reads the authenticated user`() = runTest {
            coEvery { access.listScopedSkillsForOwners(any()) } returns emptyList()
            coEvery { catalogService.listForOwners(any()) } returns emptyList()

            controller().listSkills().contextWrite(asAlice()).block()

            coVerify(exactly = 1) { catalogService.listForOwners(any()) }
        }

        @Test
        fun `shared writes need the system identity`() {
            val error = assertFailsWith<ResponseStatusException> {
                controller().addFromDirectory(SkillAddRequest(name = "pdf", sourcePath = tempRoot.toString(), shared = true))
                    .contextWrite(asAlice()).block()
            }
            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
            coVerify(exactly = 0) { refresh.addSkill(any(), any(), any()) }
        }

        @Test
        fun `the system identity may publish shared skills`() = runTest {
            val source = Files.createDirectories(sourceRoot.resolve("pdf"))
            coEvery { refresh.addSkill("system", "pdf", any()) } returns SkillAddResult.Added(entry("pdf", owner = "system"))
            coEvery { catalogService.listForOwners(any()) } returns listOf(installed(entry("pdf", owner = "system")))
            coEvery { access.listScopedSkillsForOwners(any()) } returns listOf(candidate(entry("pdf", owner = "system")))

            val response = controller().addFromDirectory(
                SkillAddRequest(name = "pdf", sourcePath = source.toString(), shared = true)
            ).block()!!

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals("pdf", response.body!!.skill!!.name)
            assertTrue(response.body!!.skill!!.shared)
        }
    }

    @Nested
    inner class `the merged listing` {

        @Test
        fun `a row lists with catalog lifecycle fields`() = runTest {
            val row = entry("pdf-report", enabled = false, indexed = false)
            coEvery { catalogService.listForOwners(any()) } returns listOf(installed(row, onDisk = false))
            coEvery { access.listScopedSkillsForOwners(any()) } returns emptyList()

            val dto = controller().listSkills().contextWrite(asAlice()).block()!!.single()

            assertEquals("pdf-report", dto.name)
            assertEquals("1.2.3", dto.version)
            assertFalse(dto.enabled)
            assertFalse(dto.installedOnDisk, "a missing directory is a restore pending, not a missing skill")
            assertFalse(dto.indexed)
            assertNull(dto.description, "content fields come from the installed copy")
        }

        @Test
        fun `an installed row carries its description and tags`() = runTest {
            val row = entry("pdf-report")
            coEvery { catalogService.listForOwners(any()) } returns listOf(installed(row))
            coEvery { access.listScopedSkillsForOwners(any()) } returns listOf(candidate(row))

            val dto = controller().listSkills().contextWrite(asAlice()).block()!!.single()

            assertEquals("PDF reports", dto.description)
            assertEquals(listOf("documents"), dto.tags)
            assertTrue(dto.indexed)
            assertFalse(dto.shared)
        }

        @Test
        fun `a shared row is marked shared`() = runTest {
            val row = entry("pdf-report", owner = "system")
            coEvery { catalogService.listForOwners(any()) } returns listOf(installed(row))
            coEvery { access.listScopedSkillsForOwners(any()) } returns listOf(candidate(row))

            assertTrue(controller().listSkills().contextWrite(asAlice()).block()!!.single().shared)
        }

        @Test
        fun `without a catalog the registry snapshot is listed`() = runTest {
            val bare = SkillController(skillAccessResolver = access)
            coEvery { access.listScopedSkillsForOwners(any()) } returns listOf(candidate())

            val listed = bare.listSkills().contextWrite(asAlice()).block()!!

            assertEquals(listOf("pdf-report"), listed.map { it.name })
            assertEquals("PDF reports", listed.single().description)
        }

        @Test
        fun `with nothing configured the listing is empty, not an error`() = runTest {
            assertEquals(emptyList<SkillDto>(), SkillController().listSkills().block())
        }
    }

    @Nested
    inner class `an install answers in the body` {

        @Test
        fun `a name collision is 409 with a reason the UI can show`() = runTest {
            val source = Files.createDirectories(sourceRoot.resolve("collide"))
            coEvery { refresh.addSkill("alice", "pdf", any()) } returns
                SkillAddResult.NameConflict("Skill 'pdf' already exists for owner 'alice'")

            val response = controller().addFromDirectory(SkillAddRequest(name = "pdf", sourcePath = source.toString()))
                .contextWrite(asAlice()).block()!!

            assertEquals(HttpStatus.CONFLICT, response.statusCode)
            assertTrue("already exists" in response.body!!.message!!)
            assertNull(response.body!!.skill)
        }

        @Test
        fun `an invalid package is 400 with the validation reason`() = runTest {
            val source = Files.createDirectories(sourceRoot.resolve("broken"))
            coEvery { refresh.addSkill("alice", "pdf", any()) } returns
                SkillAddResult.Invalid("SKILL.md is invalid: missing description")

            val response = controller().addFromDirectory(SkillAddRequest(name = "pdf", sourcePath = source.toString()))
                .contextWrite(asAlice()).block()!!

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
            assertTrue("missing description" in response.body!!.message!!)
        }

        @Test
        fun `a missing or relative source directory is refused before the pipeline`() = runTest {
            val missing = controller().addFromDirectory(SkillAddRequest(name = "pdf", sourcePath = sourceRoot.resolve("gone").toString()))
                .contextWrite(asAlice()).block()!!
            val relative = controller().addFromDirectory(SkillAddRequest(name = "pdf", sourcePath = "pdf"))
                .contextWrite(asAlice()).block()!!

            assertEquals(HttpStatus.BAD_REQUEST, missing.statusCode)
            assertEquals(HttpStatus.BAD_REQUEST, relative.statusCode)
            coVerify(exactly = 0) { refresh.addSkill(any(), any(), any()) }
        }

        @Test
        fun `a source inside another owner's skill root is refused`() = runTest {
            val foreign = Files.createDirectories(tempRoot.resolve("bob").resolve("ledger"))

            val error = assertFailsWith<ResponseStatusException> {
                controller().addFromDirectory(SkillAddRequest(name = "ledger", sourcePath = foreign.toString()))
                    .contextWrite(asAlice()).block()
            }

            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
            coVerify(exactly = 0) { refresh.addSkill(any(), any(), any()) }
        }

        @Test
        fun `a source in the caller's own root or the shared layer is installable`() = runTest {
            val mine = Files.createDirectories(tempRoot.resolve("alice").resolve("draft"))
            val shared = Files.createDirectories(tempRoot.resolve(SkillCatalogEntry.DEFAULT_USER_ID).resolve("pdf"))
            coEvery { refresh.addSkill("alice", any(), any()) } returns SkillAddResult.Added(entry("pdf"))
            coEvery { catalogService.listForOwners(any()) } returns emptyList()

            val own = controller().addFromDirectory(SkillAddRequest(name = "draft", sourcePath = mine.toString()))
                .contextWrite(asAlice()).block()!!
            val forked = controller().addFromDirectory(SkillAddRequest(name = "pdf", sourcePath = shared.toString()))
                .contextWrite(asAlice()).block()!!

            assertEquals(HttpStatus.OK, own.statusCode)
            assertEquals(HttpStatus.OK, forked.statusCode)
        }

        @Test
        fun `a successful install that shadows a shared skill warns`() = runTest {
            val source = Files.createDirectories(sourceRoot.resolve("shadow"))
            val row = entry("pdf")
            coEvery { refresh.addSkill("alice", "pdf", any()) } returns SkillAddResult.Added(row)
            coEvery { catalogService.listForOwners(any()) } returns listOf(installed(row))
            coEvery { access.listScopedSkillsForOwners(any()) } returns listOf(candidate(row))
            coEvery { store.findByName(SkillCatalogEntry.DEFAULT_USER_ID, "pdf") } returns entry("pdf", owner = "system")

            val body = controller().addFromDirectory(SkillAddRequest(name = "pdf", sourcePath = source.toString()))
                .contextWrite(asAlice()).block()!!.body!!

            assertEquals("pdf", body.skill!!.name)
            assertTrue(body.warning!!.contains("shared"), body.warning!!)
        }

        @Test
        fun `an upload delivers the staged files to the pipeline`() = runTest {
            val row = entry("pdf")
            val captured = slot<SkillUpload>()
            coEvery { refresh.addUploaded("alice", "pdf", capture(captured)) } returns SkillAddResult.Added(row)
            coEvery { catalogService.listForOwners(any()) } returns listOf(installed(row))
            coEvery { access.listScopedSkillsForOwners(any()) } returns listOf(candidate(row))

            val parts = LinkedMultiValueMap<String, Part>().apply {
                add("name", textPart("pdf"))
                add("paths", textPart("""["pdf/SKILL.md","pdf/reference.md"]"""))
                add("files", filePart(SKILL_MD))
                add("files", filePart("body".toByteArray()))
            }

            val response = controller().uploadSkill(exchangeWith(parts)).contextWrite(asAlice()).block()!!

            assertEquals(HttpStatus.OK, response.statusCode)
            val files = (captured.captured as SkillUpload.FromFiles).entries
            assertEquals(listOf("pdf/SKILL.md", "pdf/reference.md"), files.map { it.relativePath })
            assertEquals("body", String(files[1].content))
        }

        @Test
        fun `an upload whose paths and files disagree is refused`() = runTest {
            val parts = LinkedMultiValueMap<String, Part>().apply {
                add("name", textPart("pdf"))
                add("paths", textPart("""["pdf/SKILL.md"]"""))
                add("files", filePart(SKILL_MD))
                add("files", filePart("extra".toByteArray()))
            }

            val error = assertFailsWith<ResponseStatusException> {
                controller().uploadSkill(exchangeWith(parts)).contextWrite(asAlice()).block()
            }

            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
            coVerify(exactly = 0) { refresh.addUploaded(any(), any(), any()) }
        }

        @Test
        fun `a zip upload is handed over whole`() = runTest {
            val row = entry("pdf")
            val captured = slot<SkillUpload>()
            coEvery { refresh.addUploaded("alice", "pdf", capture(captured)) } returns SkillAddResult.Added(row)
            coEvery { catalogService.listForOwners(any()) } returns listOf(installed(row))
            coEvery { access.listScopedSkillsForOwners(any()) } returns emptyList()

            val parts = LinkedMultiValueMap<String, Part>().apply {
                add("name", textPart("pdf"))
                add("archive", filePart(zipOf("SKILL.md" to SKILL_MD)))
            }

            controller().uploadSkill(exchangeWith(parts)).contextWrite(asAlice()).block()

            val bytes = (captured.captured as SkillUpload.FromZip).bytes
            assertTrue(bytes.size > 0)
        }

        @Test
        fun `toggling and deleting need the skill system`() {
            val bare = SkillController()
            assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                statusOf { bare.setEnabled(SkillEnabledRequest(name = "pdf")).contextWrite(asAlice()).block() }
            )
            assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                statusOf { bare.delete("pdf").contextWrite(asAlice()).block() }
            )
        }
    }

    @Nested
    inner class `shared skills are read-only for a regular user` {

        @Test
        fun `a toggle on a shared-only name is refused`() = runTest {
            coEvery { catalogService.find("pdf", SkillOwnerContext("alice")) } returns
                installed(entry("pdf", owner = "system"))

            val error = assertFailsWith<ResponseStatusException> {
                controller().setEnabled(SkillEnabledRequest(name = "pdf", enabled = false)).contextWrite(asAlice()).block()
            }

            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
            coVerify(exactly = 0) { catalogService.setEnabled(any(), any(), any()) }
        }

        @Test
        fun `a delete of a shared-only name is refused`() = runTest {
            coEvery { catalogService.find("pdf", SkillOwnerContext("alice")) } returns
                installed(entry("pdf", owner = "system"))

            val error = assertFailsWith<ResponseStatusException> {
                controller().delete("pdf").contextWrite(asAlice()).block()
            }

            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
            coVerify(exactly = 0) { refresh.deleteSkill(any(), any()) }
        }

        @Test
        fun `an unknown name is a 404, not a silent no-op`() = runTest {
            coEvery { catalogService.find(any(), any()) } returns null

            assertEquals(
                HttpStatus.NOT_FOUND,
                statusOf { controller().setEnabled(SkillEnabledRequest(name = "ghost")).contextWrite(asAlice()).block() }
            )
        }
    }

    @Nested
    inner class `a toggle reports the index` {

        @Test
        fun `a row that flipped without the index says so`() = runTest {
            coEvery { catalogService.find(any(), any()) } returns installed(entry())
            coEvery { catalogService.setEnabled("pdf-report", any(), false) } returns
                SkillToggleResult.Applied("pdf-report", enabled = false, indexSynced = false)

            val dto = controller().setEnabled(SkillEnabledRequest(name = "pdf-report", enabled = false))
                .contextWrite(asAlice()).block()!!

            assertFalse(dto.enabled)
            assertFalse(dto.indexSynced, "the UI has to be able to say the index is still catching up")
        }

        @Test
        fun `a rejected toggle is a client error`() = runTest {
            coEvery { catalogService.find(any(), any()) } returns installed(entry())
            coEvery { catalogService.setEnabled(any(), any(), any()) } returns
                SkillToggleResult.Rejected("Skill content is unavailable")

            assertEquals(
                HttpStatus.BAD_REQUEST,
                statusOf { controller().setEnabled(SkillEnabledRequest(name = "pdf-report", enabled = true)).contextWrite(asAlice()).block() }
            )
        }

        @Test
        fun `a delete removes the owner's own row and answers 204`() = runTest {
            val row = entry()
            coEvery { catalogService.find(any(), any()) } returns installed(row)
            coEvery { refresh.deleteSkill("alice", "pdf-report") } returns row

            val response = controller().delete("pdf-report").contextWrite(asAlice()).block()!!

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
            coVerify(exactly = 1) { refresh.deleteSkill("alice", "pdf-report") }
        }
    }

    @Nested
    inner class `group-scoped management` {

        @Test
        fun `a group owner installs into the group bucket`() = runTest {
            val source = Files.createDirectories(sourceRoot.resolve("gpdf"))
            coEvery { refresh.addSkill("grp-1", "gpdf", any()) } returns
                SkillAddResult.Added(entry("gpdf", owner = "grp-1"))
            coEvery { catalogService.listForOwners(any()) } returns emptyList()

            val response = controller().addFromDirectory(
                SkillAddRequest(name = "gpdf", sourcePath = source.toString()), scope = "group"
            ).contextWrite(asGroupMember(isOwner = true)).block()!!

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals("gpdf", response.body!!.skill!!.name)
            coVerify(exactly = 1) { refresh.addSkill("grp-1", "gpdf", any()) }
        }

        @Test
        fun `a group member may not install into the group bucket`() = runTest {
            val source = Files.createDirectories(sourceRoot.resolve("gpdf2"))

            val error = assertFailsWith<ResponseStatusException> {
                controller().addFromDirectory(
                    SkillAddRequest(name = "gpdf2", sourcePath = source.toString()), scope = "group"
                ).contextWrite(asGroupMember(isOwner = false)).block()
            }

            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
            coVerify(exactly = 0) { refresh.addSkill(any(), any(), any()) }
        }

        @Test
        fun `the listing reads through the group bucket`() = runTest {
            coEvery { catalogService.listForOwners(any()) } returns emptyList()
            coEvery { access.listScopedSkillsForOwners(any()) } returns emptyList()

            controller().listSkills().contextWrite(asGroupMember(isOwner = false)).block()

            coVerify(exactly = 1) { catalogService.listForOwners(match { "grp-1" in it && "alice" in it }) }
        }

        @Test
        fun `a group-scoped toggle targets the group row`() = runTest {
            coEvery { catalogService.find("pdf", SkillOwnerContext("grp-1")) } returns
                installed(entry("pdf", owner = "grp-1"))
            coEvery { catalogService.setEnabled("pdf", SkillOwnerContext("grp-1"), false) } returns
                SkillToggleResult.Applied("pdf", enabled = false, indexSynced = true)

            val dto = controller().setEnabled(
                SkillEnabledRequest(name = "pdf", enabled = false), scope = "group"
            ).contextWrite(asGroupMember(isOwner = true)).block()!!

            assertFalse(dto.enabled)
            coVerify(exactly = 1) { catalogService.setEnabled("pdf", SkillOwnerContext("grp-1"), false) }
        }
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private fun asAlice() = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken("alice", "", emptyList<GrantedAuthority>())
    )

    /** A login carrying group claims: [isOwner] decides whether group-scoped writes are permitted. */
    private fun asGroupMember(isOwner: Boolean) = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken("alice", "", emptyList<GrantedAuthority>()).apply {
            details = com.easy.easyai.auth.group.GroupClaims(
                owners = listOf("alice", "grp-1"),
                groupId = "g1",
                groupUserId = "grp-1",
                isGroupOwner = isOwner
            )
        }
    )

    private fun exchangeWith(parts: MultiValueMap<String, Part>): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.multipartData } returns Mono.just(parts)
        every { exchange.request } returns mockk<ServerHttpRequest>(relaxed = true)
        return exchange
    }

    private fun filePart(bytes: ByteArray): FilePart = mockk {
        every { content() } returns Mono.just(bufferFactory().wrap(bytes)).flux()
    }

    private fun textPart(text: String): Part = filePart(text.toByteArray())

    private fun bufferFactory(): DataBufferFactory = DefaultDataBufferFactory.sharedInstance

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        val tempRoot: Path = Path.of(System.getProperty("java.io.tmpdir"), "easyai-skill-controller-test")

        /** Install sources live outside the skill tree: inside it, every directory is an owner root. */
        val sourceRoot: Path = Path.of(System.getProperty("java.io.tmpdir"), "easyai-skill-controller-test-sources")
        val SKILL_MD: ByteArray = "---\nname: pdf\ndescription: PDF\n---\nbody".toByteArray()
        const val CHECKSUM = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
