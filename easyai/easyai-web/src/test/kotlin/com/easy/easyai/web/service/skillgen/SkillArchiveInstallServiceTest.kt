package com.easy.easyai.web.service.skillgen

import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.skills.SkillAddResult
import com.easy.easyai.skills.SkillArchiveInspector
import com.easy.easyai.skills.SkillConfig
import com.easy.easyai.skills.SkillRefreshService
import com.easy.easyai.skills.SkillUpload
import com.easy.easyai.web.model.SkillArchiveEntryRequest
import com.easy.easyai.web.model.SkillArchiveInstallRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillArchiveInstallServiceTest {

    private val owner = "joe"
    private val inspector = SkillArchiveInspector()

    private fun skillMd(name: String, body: String = "# $name\n\nFollow these steps."): ByteArray = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("description: Use when running the $name skill")
        appendLine("---")
        appendLine()
        appendLine(body)
    }.toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { out ->
                entries.forEach { (name, content) ->
                    // A fixed timestamp keeps the archive bytes (and therefore its sha256) stable.
                    out.putNextEntry(ZipEntry(name).apply { time = 0L })
                    out.write(content)
                    out.closeEntry()
                }
            }
        }.toByteArray()

    private fun twoSkillArchive(): ByteArray = zip(
        "a/SKILL.md" to skillMd("alpha"),
        "b/SKILL.md" to skillMd("beta")
    )

    private fun request(
        entries: List<SkillArchiveEntryRequest>,
        sha256: String = inspector.sha256Hex(twoSkillArchive())
    ) = SkillArchiveInstallRequest(
        url = "https://example.com/skills.zip",
        sha256 = sha256,
        entries = entries
    )

    private fun rpc(): SkillRefreshService = mockk()

    private fun row(name: String) = SkillCatalogEntry(
        name = name,
        checksum = "x",
        rootPath = "/tmp/skills/joe",
        installPath = "/tmp/skills/joe/$name",
        userId = owner
    )

    private suspend fun install(
        service: SkillArchiveInstallService,
        bytes: ByteArray,
        request: SkillArchiveInstallRequest
    ) = service.installVerified(owner, bytes, request)

    @Test
    fun `both skills install in one batch`() = runTest {
        val refresh = rpc()
        coEvery { refresh.addUploaded(owner, "alpha", any()) } returns SkillAddResult.Added(row("alpha"))
        coEvery { refresh.addUploaded(owner, "beta", any()) } returns SkillAddResult.Added(row("beta"))
        val service = SkillArchiveInstallService(refresh, SkillConfig())

        val response = install(
            service,
            twoSkillArchive(),
            request(listOf(SkillArchiveEntryRequest("a", "alpha"), SkillArchiveEntryRequest("b", "beta")))
        )

        assertEquals(listOf("installed", "installed"), response.results.map { it.outcome })
    }

    @Test
    fun `one conflict does not roll back the other entry`() = runTest {
        val refresh = rpc()
        coEvery { refresh.addUploaded(owner, "alpha", any()) } returns
            SkillAddResult.NameConflict("Skill 'alpha' already exists")
        coEvery { refresh.addUploaded(owner, "beta", any()) } returns SkillAddResult.Added(row("beta"))
        val service = SkillArchiveInstallService(refresh, SkillConfig())

        val response = install(
            service,
            twoSkillArchive(),
            request(listOf(SkillArchiveEntryRequest("a", "alpha"), SkillArchiveEntryRequest("b", "beta")))
        )

        assertEquals(listOf("conflict", "installed"), response.results.map { it.outcome })
        assertContains(response.results.first().message!!, "already exists")
    }

    @Test
    fun `a changed archive is refused before installing anything`() = runTest {
        val refresh = rpc()
        val service = SkillArchiveInstallService(refresh, SkillConfig())

        val error = assertFailsWith<ResponseStatusException> {
            install(
                service,
                twoSkillArchive(),
                request(listOf(SkillArchiveEntryRequest("a", "alpha")), sha256 = "0".repeat(64))
            )
        }

        assertEquals(400, error.statusCode.value())
        assertContains(error.reason!!, "sha256 mismatch")
        coVerify(exactly = 0) { refresh.addUploaded(any(), any(), any()) }
    }

    @Test
    fun `an entry path missing from the archive is reported missing`() = runTest {
        val refresh = rpc()
        coEvery { refresh.addUploaded(owner, "alpha", any()) } returns SkillAddResult.Added(row("alpha"))
        val service = SkillArchiveInstallService(refresh, SkillConfig())

        val response = install(
            service,
            twoSkillArchive(),
            request(listOf(SkillArchiveEntryRequest("a", "alpha"), SkillArchiveEntryRequest("gone", "ghost")))
        )

        assertEquals(listOf("installed", "missing"), response.results.map { it.outcome })
        assertContains(response.results[1].message!!, "no longer contains")
    }

    @Test
    fun `the install name is applied as a frontmatter rename`() = runTest {
        val refresh = rpc()
        val upload = slot<SkillUpload>()
        coEvery { refresh.addUploaded(owner, "news", capture(upload)) } returns SkillAddResult.Added(row("news"))
        val service = SkillArchiveInstallService(refresh, SkillConfig())

        install(
            service,
            twoSkillArchive(),
            request(listOf(SkillArchiveEntryRequest("a", "news")))
        )

        val files = assertIs<SkillUpload.FromFiles>(upload.captured).entries
        val skillFile = files.single { it.relativePath == "SKILL.md" }
        val content = String(skillFile.content)
        assertTrue(Regex("(?m)^name: \"?news\"?$").containsMatchIn(content), content)
    }

    @Test
    fun `a blocking package is reported invalid instead of installed`() = runTest {
        val refresh = rpc()
        val archive = zip("SKILL.md" to skillMd("leaky", body = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA"))
        val service = SkillArchiveInstallService(refresh, SkillConfig())

        val response = install(
            service,
            archive,
            request(listOf(SkillArchiveEntryRequest("", "leaky")), sha256 = inspector.sha256Hex(archive))
        )

        assertEquals("invalid", response.results.single().outcome)
        assertContains(response.results.single().message!!, "Blocked by the server scan")
        coVerify(exactly = 0) { refresh.addUploaded(any(), any(), any()) }
    }

    @Test
    fun `an invalid name is reported without touching the pipeline`() = runTest {
        val refresh = rpc()
        val service = SkillArchiveInstallService(refresh, SkillConfig())

        val response = install(
            service,
            twoSkillArchive(),
            request(listOf(SkillArchiveEntryRequest("a", "Bad Name")))
        )

        assertEquals("invalid", response.results.single().outcome)
        coVerify(exactly = 0) { refresh.addUploaded(any(), any(), any()) }
    }

    @Test
    fun `an install failure on one entry is isolated`() = runTest {
        val refresh = rpc()
        coEvery { refresh.addUploaded(owner, "alpha", any()) } throws IllegalStateException("disk full")
        coEvery { refresh.addUploaded(owner, "beta", any()) } returns SkillAddResult.Added(row("beta"))
        val service = SkillArchiveInstallService(refresh, SkillConfig())

        val response = install(
            service,
            twoSkillArchive(),
            request(listOf(SkillArchiveEntryRequest("a", "alpha"), SkillArchiveEntryRequest("b", "beta")))
        )

        assertEquals(listOf("invalid", "installed"), response.results.map { it.outcome })
        assertContains(response.results.first().message!!, "disk full")
    }

    @Test
    fun `duplicate entry paths are a bad request`() = runTest {
        val service = SkillArchiveInstallService(rpc(), SkillConfig())

        val error = assertFailsWith<ResponseStatusException> {
            install(
                service,
                twoSkillArchive(),
                request(listOf(SkillArchiveEntryRequest("a", "alpha"), SkillArchiveEntryRequest("a", "beta")))
            )
        }

        assertEquals(400, error.statusCode.value())
        assertContains(error.reason!!, "Duplicate entryPath")
    }

    @Test
    fun `an empty selection is a bad request`() = runTest {
        val service = SkillArchiveInstallService(rpc(), SkillConfig())

        val error = assertFailsWith<ResponseStatusException> {
            install(service, twoSkillArchive(), request(emptyList()))
        }

        assertEquals(400, error.statusCode.value())
    }

    @Test
    fun `an archive over the configured cap is refused`() = runTest {
        val service = SkillArchiveInstallService(rpc(), SkillConfig(packageMaxBytes = 16))

        val error = assertFailsWith<ResponseStatusException> {
            install(service, twoSkillArchive(), request(listOf(SkillArchiveEntryRequest("a", "alpha"))))
        }

        assertEquals(400, error.statusCode.value())
        assertContains(error.reason!!, SkillArchiveCodes.TOO_LARGE)
    }

    @Test
    fun `a missing skill system is service unavailable`() = runTest {
        val service = SkillArchiveInstallService(null, SkillConfig())

        val error = assertFailsWith<ResponseStatusException> {
            install(service, twoSkillArchive(), request(listOf(SkillArchiveEntryRequest("a", "alpha"))))
        }

        assertEquals(503, error.statusCode.value())
    }
}
