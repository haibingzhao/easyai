package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SkillCatalogSyncService], the disk-to-catalog alignment layer.
 *
 * The claim path matters because a skill nobody ever installed has no row until this service
 * writes one — and without a row it belongs to no owner and cannot be found by `skill_search`.
 * The drift classification matters just as much: only [SkillDrift.Content] may cost a backend
 * write, which is what keeps a steady-state boot free of HTTP traffic.
 */
class SkillCatalogSyncServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val catalog = mockk<AsyncSkillCatalogStore>(relaxed = true)

    private fun sync(config: SkillConfig = SkillConfig(paths = listOf(tempDir.toString()))) =
        SkillCatalogSyncService(catalog, config, mockk<SkillRegistry>(relaxed = true))

    /** Create `{tempDir}/{name}/SKILL.md` with [body] and return the skill directory. */
    private fun skillDir(name: String, body: String = "---\nname: $name\ndescription: d\n---\n\nbody\n"): Path {
        val dir = tempDir.resolve(name)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("SKILL.md"), body)
        return dir
    }

    private fun discovered(dir: Path, name: String = dir.fileName.toString()) = SkillInfo(
        name = name,
        description = "d",
        location = dir.resolve("SKILL.md"),
        content = "body"
    )

    private fun row(
        dir: Path,
        name: String = dir.fileName.toString(),
        id: String = "row-$name-${uuid()}",
        userId: String = "alice"
    ) =
        SkillCatalogEntry(
            id = id,
            name = name,
            checksum = SkillChecksums.sha256Hex(Files.readAllBytes(dir.resolve("SKILL.md"))),
            installPath = dir.toString(),
            userId = userId
        )

    private fun capturedClaim(): CapturingSlot<SkillCatalogEntry> {
        val captured = slot<SkillCatalogEntry>()
        coEvery { catalog.claim(capture(captured)) } answers { firstArg() }
        return captured
    }

    @Nested
    inner class `claiming pre-existing skills` {

        @Test
        fun `a discovered skill gets one LOCAL row owned by the default user`() = runTest {
            val dir = skillDir("pdf-report")
            val captured = capturedClaim()
            coEvery { catalog.listByName(any(), any()) } returns emptyList()

            val created = sync().claimUnclaimed(listOf(discovered(dir)), requestedUserId = null).claimed

            assertEquals(1, created)
            val entry = captured.captured
            assertEquals("pdf-report", entry.name)
            assertEquals(SkillCatalogEntry.SOURCE_LOCAL, entry.source)
            assertEquals(SkillCatalogEntry.DEFAULT_USER_ID, entry.userId)
            assertEquals(dir.toAbsolutePath().normalize().toString(), entry.installPath)
            assertEquals(SkillChecksums.sha256Hex(Files.readAllBytes(dir.resolve("SKILL.md"))), entry.checksum)
            assertTrue(entry.enabled, "a claimed skill is usable until somebody switches it off")
            assertEquals(SkillCatalogEntry.GLOBAL_HASH, entry.projectHash, "a path outside any workspace claims GLOBAL")
        }

        @Test
        fun `the version declared in the frontmatter is claimed with the row`() = runTest {
            val dir = skillDir("versioned", "---\nname: versioned\ndescription: d\nversion: 1.4.2\n---\n\nbody\n")
            val captured = capturedClaim()
            coEvery { catalog.listByName(any(), any()) } returns emptyList()

            sync().claimUnclaimed(listOf(discovered(dir)), requestedUserId = null).claimed

            assertEquals("1.4.2", captured.captured.version)
        }

        @Test
        fun `a second pass claims nothing, so startup is idempotent`() = runTest {
            val dir = skillDir("pdf-report")
            val existing = row(dir, userId = SkillCatalogEntry.DEFAULT_USER_ID)
            // All owners reserve their installation paths before claims begin.
            coEvery { catalog.listAll() } returns listOf(existing)

            val created = sync().claimUnclaimed(listOf(discovered(dir)), requestedUserId = null).claimed

            assertEquals(0, created)
            coVerify(exactly = 0) { catalog.claim(any()) }
        }

        @Test
        fun `a same-named skill of another project is claimed as its own row`() = runTest {
            // Idempotency is per install directory: the other project's row must not suppress this claim.
            val config = SkillConfig(homeSkillDirs = listOf(".easyai/skills"))
            val project = tempDir.resolve("repo")
            val dir = project.resolve(".easyai/skills/pdf").also { Files.createDirectories(it) }
            Files.writeString(dir.resolve("SKILL.md"), "---\nname: pdf\ndescription: d\n---\n\nbody\n")
            val otherDir = tempDir.resolve("other/.easyai/skills/pdf").also { Files.createDirectories(it) }
            Files.writeString(otherDir.resolve("SKILL.md"), "---\nname: pdf\ndescription: d\n---\n\nbody\n")
            val otherProject = tempDir.resolve("other")
            val otherProjectRow = row(otherDir, name = "pdf", userId = SkillCatalogEntry.DEFAULT_USER_ID).copy(
                projectHash = SkillScopeResolver.projectHashOf(otherProject),
                indexProjectPath = SkillPaths.canonicalize(otherProject)
            )
            coEvery { catalog.listAll() } returns listOf(otherProjectRow)
            val captured = capturedClaim()

            val created = sync(config).claimUnclaimed(listOf(discovered(dir)), "alice", project).claimed

            assertEquals(1, created, "a row for a different directory is not this skill's row")
            assertEquals(
                SkillScopeResolver.projectHashOf(project),
                captured.captured.projectHash,
                "the granularity token must match what the unique index expects for this project"
            )
            assertEquals(SkillPaths.canonicalize(project), captured.captured.indexProjectPath)
        }

        @Test
        fun `a skill whose file cannot be read is skipped, not claimed`() = runTest {
            val ghost = tempDir.resolve("ghost")
            coEvery { catalog.listByName(any(), any()) } returns emptyList()

            val created = sync().claimUnclaimed(listOf(discovered(ghost)), requestedUserId = null).claimed

            assertEquals(0, created)
            coVerify(exactly = 0) { catalog.claim(any()) }
        }

        @Test
        fun `without a catalog there is nothing to claim`() = runTest {
            val result = SkillCatalogSyncService(null).claimUnclaimed(
                listOf(discovered(skillDir("pdf-report"))), requestedUserId = null
            )
            assertEquals(0, result.claimed)
            assertEquals(1, result.unclaimed)
        }
    }

    @Nested
    inner class `drift classification` {

        @Test
        fun `unchanged content costs no write`() = runTest {
            val dir = skillDir("stable")
            val sync = sync()

            assertIs<SkillDrift.None>(sync.driftOf(row(dir, id = "stable-1")))
        }

        @Test
        fun `changed bytes are reported as content drift with the new fingerprint`() = runTest {
            val dir = skillDir("edited")
            val sync = sync()
            val original = row(dir, id = "edited-1")

            Files.writeString(dir.resolve("SKILL.md"), "---\nname: edited\ndescription: d\nversion: 2.0.0\n---\n\nnew body\n")
            val drift = sync.driftOf(original)

            assertIs<SkillDrift.Content>(drift)
            assertEquals(SkillChecksums.sha256Hex(Files.readAllBytes(dir.resolve("SKILL.md"))), drift.newChecksum)
            assertEquals("2.0.0", drift.newVersion)
        }

        @Test
        fun `a touched file with identical bytes is still not drift`() = runTest {
            val dir = skillDir("touched")
            val sync = sync()
            val original = row(dir, id = "touched-1")

            assertTrue(dir.resolve("SKILL.md").toFile().setLastModified(System.currentTimeMillis() + 60_000L))

            assertIs<SkillDrift.None>(sync.driftOf(original), "mtime moves without content; only the hash arbitrates")
        }

        @Test
        fun `a deleted skill tree is reported as missing`() = runTest {
            val dir = skillDir("removed")
            val sync = sync()
            val original = row(dir, id = "removed-1")
            deleteRecursively(dir)

            assertIs<SkillDrift.Missing>(sync.driftOf(original))
        }

        @Test
        fun `a row whose file exists but whose directory was swapped for a file is missing`() = runTest {
            val dir = skillDir("hijacked")
            val sync = sync()
            val original = row(dir, id = "hijacked-1")
            deleteRecursively(dir)
            Files.writeString(dir, "not a directory any more")

            assertIs<SkillDrift.Missing>(sync.driftOf(original))
        }

        @Test
        fun `same size and mtime still require comparing the content bytes`() = runTest {
            val dir = skillDir("verified")
            val sync = sync()
            val original = row(dir, id = "verified-1")
            assertIs<SkillDrift.None>(sync.driftOf(original))

            val file = dir.resolve("SKILL.md")
            val stamp = Files.getLastModifiedTime(file)
            val size = Files.size(file)
            Files.writeString(file, "---\nname: verified\ndescription: d\n---\n\nedit\n")
            Files.setLastModifiedTime(file, stamp)
            assertEquals(size, Files.size(file))
            assertEquals(stamp, Files.getLastModifiedTime(file))
            assertIs<SkillDrift.Content>(sync.driftOf(original), "explicit refresh must hash even at identical mtime")
            assertIs<SkillDrift.Content>(sync.driftOf(original))
        }
    }

    @Nested
    inner class `fingerprints and versions` {

        @Test
        fun `checksumOf matches the claimed content fingerprint`() = runTest {
            val dir = skillDir("hashing")

            assertEquals(
                SkillChecksums.sha256Hex(Files.readAllBytes(dir.resolve("SKILL.md"))),
                sync().checksumOf(row(dir, id = "hashing-1"))
            )
        }

        @Test
        fun `checksumOf is null when the file is gone`() = runTest {
            val sync = sync()
            val dir = skillDir("gone")
            val original = row(dir, id = "gone-1")
            deleteRecursively(dir)

            assertNull(sync.checksumOf(original))
        }

        @Test
        fun `declaredVersionOf falls back when no version is written`() = runTest {
            val dir = skillDir("unversioned")

            assertEquals(
                SkillCatalogEntry.DEFAULT_VERSION,
                sync().declaredVersionOf(row(dir, id = "unversioned-1"))
            )
        }
    }

    private fun deleteRecursively(dir: Path) {
        assertTrue(dir.toFile().deleteRecursively(), "test setup could not remove $dir")
    }

    private companion object {
        fun uuid(): String = UUID.randomUUID().toString().take(8)
    }
}
