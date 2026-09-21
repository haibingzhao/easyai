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

    private fun capturedUpsert(): CapturingSlot<SkillCatalogEntry> {
        val captured = slot<SkillCatalogEntry>()
        coEvery { catalog.upsert(capture(captured)) } answers { firstArg() }
        return captured
    }

    @Nested
    inner class `claiming pre-existing skills` {

        @Test
        fun `a discovered skill gets one LOCAL row owned by the default user`() = runTest {
            val dir = skillDir("pdf-report")
            val captured = capturedUpsert()
            coEvery { catalog.listByName(any(), any()) } returns emptyList()

            val created = SkillCatalogSyncService(catalog).backfillAll(listOf(discovered(dir)))

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
            val captured = capturedUpsert()
            coEvery { catalog.listByName(any(), any()) } returns emptyList()

            SkillCatalogSyncService(catalog).backfillAll(listOf(discovered(dir)))

            assertEquals("1.4.2", captured.captured.version)
        }

        @Test
        fun `a second pass claims nothing, so startup is idempotent`() = runTest {
            val dir = skillDir("pdf-report")
            val existing = row(dir, userId = SkillCatalogEntry.DEFAULT_USER_ID)
            // M6: backfillAll now preloads the owner's whole slice in one round trip rather than
            // doing a `listByName` per discovered skill.
            coEvery { catalog.listByUser(SkillCatalogEntry.DEFAULT_USER_ID) } returns listOf(existing)

            val created = SkillCatalogSyncService(catalog).backfillAll(listOf(discovered(dir)))

            assertEquals(0, created)
            coVerify(exactly = 0) { catalog.upsert(any()) }
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
            val otherProjectRow = row(otherDir, name = "pdf", userId = SkillCatalogEntry.DEFAULT_USER_ID)
            coEvery { catalog.listByUser(SkillCatalogEntry.DEFAULT_USER_ID) } returns listOf(otherProjectRow)
            val captured = capturedUpsert()

            val created = SkillCatalogSyncService(catalog, config).backfillAll(listOf(discovered(dir)))

            assertEquals(1, created, "a row for a different directory is not this skill's row")
            assertEquals(
                SkillScopeResolver.projectHashOf(project),
                captured.captured.projectHash,
                "the granularity token must match what the unique index expects for this project"
            )
        }

        @Test
        fun `a skill whose file cannot be read is skipped, not claimed`() = runTest {
            val ghost = tempDir.resolve("ghost")
            coEvery { catalog.listByName(any(), any()) } returns emptyList()

            val created = SkillCatalogSyncService(catalog).backfillAll(listOf(discovered(ghost)))

            assertEquals(0, created)
            coVerify(exactly = 0) { catalog.upsert(any()) }
        }

        @Test
        fun `without a catalog there is nothing to claim`() = runTest {
            assertEquals(0, SkillCatalogSyncService(null).backfillAll(listOf(discovered(skillDir("pdf-report")))))
        }
    }

    @Nested
    inner class `drift classification` {

        @Test
        fun `unchanged content costs no write`() = runTest {
            val dir = skillDir("stable")
            val sync = SkillCatalogSyncService(catalog)

            assertIs<SkillDrift.None>(sync.driftOf(row(dir, id = "stable-1")))
        }

        @Test
        fun `changed bytes are reported as content drift with the new fingerprint`() = runTest {
            val dir = skillDir("edited")
            val sync = SkillCatalogSyncService(catalog)
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
            val sync = SkillCatalogSyncService(catalog)
            val original = row(dir, id = "touched-1")

            assertTrue(dir.resolve("SKILL.md").toFile().setLastModified(System.currentTimeMillis() + 60_000L))

            assertIs<SkillDrift.None>(sync.driftOf(original), "mtime moves without content; only the hash arbitrates")
        }

        @Test
        fun `a deleted skill tree is reported as missing`() = runTest {
            val dir = skillDir("removed")
            val sync = SkillCatalogSyncService(catalog)
            val original = row(dir, id = "removed-1")
            deleteRecursively(dir)

            assertIs<SkillDrift.Missing>(sync.driftOf(original))
        }

        @Test
        fun `a row whose file exists but whose directory was swapped for a file is missing`() = runTest {
            val dir = skillDir("hijacked")
            val sync = SkillCatalogSyncService(catalog)
            val original = row(dir, id = "hijacked-1")
            deleteRecursively(dir)
            Files.writeString(dir, "not a directory any more")

            assertIs<SkillDrift.Missing>(sync.driftOf(original))
        }

        @Test
        fun `invalidate forces the next comparison to re-read the file`() = runTest {
            val dir = skillDir("memoised")
            val sync = SkillCatalogSyncService(catalog)
            val original = row(dir, id = "memoised-1")
            assertIs<SkillDrift.None>(sync.driftOf(original))

            // Same mtime, different bytes: only an invalidated cache can notice.
            val file = dir.resolve("SKILL.md")
            val stamp = file.toFile().lastModified()
            Files.writeString(file, "---\nname: memoised\ndescription: d\n---\n\nsilently edited\n")
            file.toFile().setLastModified(stamp)
            assertIs<SkillDrift.None>(sync.driftOf(original), "the mtime short-circuit must be observable")

            sync.invalidate(original.id)
            assertIs<SkillDrift.Content>(sync.driftOf(original))
        }
    }

    @Nested
    inner class `fingerprints and versions` {

        @Test
        fun `checksumOf matches what backfill would have stored`() = runTest {
            val dir = skillDir("hashing")

            assertEquals(
                SkillChecksums.sha256Hex(Files.readAllBytes(dir.resolve("SKILL.md"))),
                SkillCatalogSyncService(catalog).checksumOf(row(dir, id = "hashing-1"))
            )
        }

        @Test
        fun `checksumOf is null when the file is gone`() = runTest {
            val sync = SkillCatalogSyncService(catalog)
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
                SkillCatalogSyncService(catalog).declaredVersionOf(row(dir, id = "unversioned-1"))
            )
        }
    }

    private fun deleteRecursively(dir: Path) {
        assertTrue(dir.toFile().deleteRecursively(), "test setup could not remove $dir")
    }

    private companion object {
        /** Keeps row ids unique so the process-local mtime cache of one case cannot serve another. */
        fun uuid(): String = UUID.randomUUID().toString().take(8)
    }
}
