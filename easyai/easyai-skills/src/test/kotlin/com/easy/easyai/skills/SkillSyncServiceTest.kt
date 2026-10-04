package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectStorageResolver
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SkillSyncService] — the single write direction between disk, catalog and packages.
 *
 * The three reconcile edges (claim / restore / push) and their failure modes are exercised here;
 * the end-to-end composition with the index projection lives in `SkillRefreshEndToEndTest`.
 */
class SkillSyncServiceTest {
    @TempDir lateinit var temp: Path

    @Nested
    inner class OwnerNormalisation {
        @Test
        fun `blank and absent identities collapse to the shared layer`() {
            val chain = SkillSyncFixture(temp)
            for (userId in listOf(null, " ", SkillCatalogEntry.DEFAULT_USER_ID)) {
                assertEquals(SkillCatalogEntry.DEFAULT_USER_ID, chain.sync.ownerOf(userId))
            }
            assertEquals("alice", chain.sync.ownerOf("alice"))
        }

        @Test
        fun `without a catalog a sync only rescans the owner roots`() = runTest {
            val config = SkillConfig(rootDir = temp.resolve("root").createDirectories().toString())
            val registry = DefaultSkillRegistry(DefaultSkillDiscovery(), config)
            val draft = SkillPaths.installDir(SkillPaths.ownerRoot(config, "alice"), "draft").createDirectories()
            draft.resolve(SkillPaths.SKILL_FILE_NAME).writeText("---\nname: draft\ndescription: d\n---\nbody")
            val sync = SkillSyncService(null, registry, DefaultSkillDiscovery(), SkillPackageStore(null, TestObjectStorage()), config)

            val outcome = sync.syncFor("alice")

            assertEquals(1, outcome.delta?.total)
            assertEquals(listOf("draft"), registry.visibleFor("alice").map { it.name })
        }
    }

    @Nested
    inner class Restore {
        @Test
        fun `a tampered package is refused and the directory stays absent`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.sync.syncFor("alice")
            val row = chain.catalog.allRows().single()
            SkillPackages.deleteTree(file.parent)
            chain.storage.objects[row.objectKey] = chain.storage.objects.getValue(row.objectKey).copy(bytes = ByteArray(0))

            val outcome = chain.sync.syncFor("alice")

            assertEquals(1, outcome.skipped)
            assertEquals(0, outcome.restored)
            assertTrue(Files.notExists(file.parent))
        }

        @Test
        fun `a row whose install path is outside its owner root is never restored`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft", owner = "alice")
            chain.sync.syncFor("alice")
            val row = chain.catalog.allRows().single()
            SkillPackages.deleteTree(Path.of(row.installPath))
            val elsewhere = temp.resolve("elsewhere")
            chain.catalog.replace(
                row.copy(
                    installPath = SkillPaths.canonicalize(elsewhere.resolve("draft")),
                    rootPath = SkillPaths.canonicalize(elsewhere)
                )
            )

            val outcome = chain.sync.syncFor("alice")

            assertEquals(1, outcome.skipped)
            assertTrue(Files.notExists(elsewhere.resolve("draft")))
        }

        @Test
        fun `a zip-slip package cannot write outside the target directory`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.sync.syncFor("alice")
            val row = chain.catalog.allRows().single()
            SkillPackages.deleteTree(file.parent)

            val zip = ByteArrayOutputStream().also { buffer ->
                ZipOutputStream(buffer).use { out ->
                    out.putNextEntry(ZipEntry("../victim.txt"))
                    out.write("escape".toByteArray())
                    out.closeEntry()
                }
            }.toByteArray()
            val stored = chain.storage.objects.getValue(row.objectKey)
            chain.storage.objects[row.objectKey] = ObjectContent(stored.meta, zip)

            val outcome = chain.sync.syncFor("alice")

            assertEquals(1, outcome.skipped)
            assertTrue(Files.notExists(file.parent.parent.resolve("victim.txt")))
            assertTrue(Files.notExists(file.parent))
        }
    }

    @Nested
    inner class Push {
        @Test
        fun `drifted directory re-uploads the same object key and CAS-updates the row`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.sync.syncFor("alice")
            val before = chain.catalog.allRows().single()
            val bytesBefore = chain.storage.objects.getValue(before.objectKey).bytes.copyOf()

            file.writeText("---\nname: draft\ndescription: d\n---\nchanged")
            val outcome = chain.sync.syncFor("alice")
            val row = chain.catalog.allRows().single()

            assertEquals(1, outcome.pushed)
            assertEquals(before.objectKey, row.objectKey)
            assertEquals(SkillChecksums.dirDigest(file.parent), row.checksum)
            assertTrue(!chain.storage.objects.getValue(row.objectKey).bytes.contentEquals(bytesBefore))
        }

        @Test
        fun `a lost CAS leaves the row content untouched`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.sync.syncFor("alice")
            val row = chain.catalog.allRows().single()
            file.writeText("---\nname: draft\ndescription: d\n---\nchanged")
            // Interleave a toggle so the sync pass reads the row and then loses the CAS anyway:
            // fail the content update explicitly.
            chain.catalog.failContentUpdates = true

            val outcome = chain.sync.syncFor("alice")

            assertEquals(0, outcome.pushed)
            assertEquals(1, outcome.skipped)
            assertEquals(row.checksum, chain.catalog.findByName("alice", "draft")!!.checksum)
        }

        @Test
        fun `a package store outage fails the push without corrupting the row`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.sync.syncFor("alice")
            val before = chain.catalog.allRows().single()
            file.writeText("---\nname: draft\ndescription: d\n---\nchanged")
            chain.storage.failPut = true

            val outcome = chain.sync.syncFor("alice")

            assertEquals(1, outcome.failed)
            assertEquals(before.checksum, chain.catalog.allRows().single().checksum)
        }
    }

    @Nested
    inner class Backfill {
        /** A sync service reading the same catalog + registry but resolving a different storage. */
        private fun storageOf(chain: SkillSyncFixture, target: TestObjectStorage): SkillSyncService {
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { resolver.resolve(any()) } returns target
            return SkillSyncService(
                chain.catalog, chain.registry, DefaultSkillDiscovery(),
                SkillPackageStore(resolver, chain.storage), chain.config
            )
        }

        @Test
        fun `a package absent from the newly resolved storage is re-uploaded without touching the row`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft", owner = "alice")
            chain.sync.syncFor("alice")
            val before = chain.catalog.allRows().single()
            assertNotNull(chain.storage.objects[before.objectKey], "the claim wrote into the local fallback")

            val oss = TestObjectStorage()
            val outcome = storageOf(chain, oss).syncFor("alice")

            assertEquals(1, outcome.backfilled)
            assertEquals(0, outcome.pushed)
            val row = chain.catalog.allRows().single()
            assertEquals(before.revision, row.revision)
            assertEquals(before.checksum, row.checksum)
            assertEquals(before.indexedChecksum, row.indexedChecksum)
            // The bytes must be the working copy the row already describes, not something new.
            val target = temp.resolve("backfilled").createDirectories()
            SkillPackages.unpack(oss.objects.getValue(row.objectKey).bytes, target, chain.config.packageMaxBytes)
            assertEquals(row.checksum, SkillChecksums.dirDigest(target))
        }

        @Test
        fun `an existing package is left alone on every later pass`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft", owner = "alice")
            chain.sync.syncFor("alice")
            val oss = TestObjectStorage()
            val mirror = storageOf(chain, oss)

            assertEquals(1, mirror.syncFor("alice").backfilled)
            assertEquals(0, mirror.syncFor("alice").backfilled)
            assertEquals(1, oss.objects.size)
        }

        @Test
        fun `several quiet rows cost one listing and no per-row stat`() = runTest {
            val chain = SkillSyncFixture(temp)
            for (name in listOf("one", "two", "three")) chain.write(name, owner = "alice")
            chain.sync.syncFor("alice")
            val oss = TestObjectStorage()
            oss.objects.putAll(chain.storage.objects)
            val mirror = storageOf(chain, oss)

            val outcome = mirror.syncFor("alice")

            assertEquals(0, outcome.backfilled)
            assertEquals(0, oss.headCalls)
            // The requester's namespace and the shared layer are the two a user pass touches.
            assertEquals(listOf("skills/system/", "skills/alice/"), oss.listedPrefixes)
        }

        @Test
        fun `a namespace the backend will not list falls back to per-row stats`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft", owner = "alice")
            chain.sync.syncFor("alice")
            val oss = TestObjectStorage()
            oss.failList = true
            val mirror = storageOf(chain, oss)

            val outcome = mirror.syncFor("alice")

            assertEquals(1, outcome.backfilled)
            assertEquals(1, oss.headCalls)
        }

        @Test
        fun `an unreachable storage reports the row as failed and leaves the working copy`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.sync.syncFor("alice")
            val before = chain.catalog.allRows().single()
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { resolver.resolve(any()) } throws IllegalStateException("bucket unreachable")
            val mirror = SkillSyncService(
                chain.catalog, chain.registry, DefaultSkillDiscovery(),
                SkillPackageStore(resolver, chain.storage), chain.config
            )

            val outcome = mirror.syncFor("alice")

            assertEquals(1, outcome.failed)
            assertEquals(0, outcome.backfilled)
            assertEquals(before.revision, chain.catalog.allRows().single().revision)
            assertTrue(Files.isRegularFile(file))
        }
    }

    @Nested
    inner class SharedLayer {
        @Test
        fun `a directory-only skill in the shared layer is never claimed`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("shared", owner = SkillModelFixture.SYSTEM)

            val outcome = chain.sync.syncFor(SkillCatalogEntry.DEFAULT_USER_ID)

            assertEquals(0, outcome.claimed)
            assertEquals(1, outcome.unclaimed)
            assertTrue(chain.catalog.allRows().isEmpty(), "the shared root creates no rows from disk")
            assertTrue(chain.storage.objects.isEmpty(), "an ignored directory must never be packed")
        }

        @Test
        fun `a foreign pass over the shared root leaves its directories on disk`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("shared", owner = SkillModelFixture.SYSTEM)
            val digest = SkillChecksums.dirDigest(file.parent)

            // Every request sweeps [system] alongside the requester, so this is the path that used
            // to publish a hand-placed shared skill to the whole machine.
            val outcome = chain.sync.syncFor("alice")

            assertEquals(1, outcome.unclaimed)
            assertEquals(0, outcome.claimed)
            assertTrue(Files.isRegularFile(file))
            assertEquals(digest, SkillChecksums.dirDigest(file.parent))
            assertTrue(chain.catalog.allRows().isEmpty())
        }

        @Test
        fun `drifted content of a claimed system row still pushes`() = runTest {
            val chain = SkillSyncFixture(temp)
            val source = chain.staging("shared")
            source.resolve(SkillPaths.SKILL_FILE_NAME)
                .writeText("---\nname: shared\ndescription: d\n---\nbuilt-in")
            val added = chain.sync.addSkill(SkillCatalogEntry.DEFAULT_USER_ID, "shared", source)
            assertTrue(added is SkillAddResult.Added, "got: $added")
            val file = SkillPaths.installDir(
                SkillPaths.ownerRoot(chain.config, SkillCatalogEntry.DEFAULT_USER_ID), "shared"
            ).resolve(SkillPaths.SKILL_FILE_NAME)
            file.writeText("---\nname: shared\ndescription: d\n---\nedited by the admin")

            val outcome = chain.sync.syncFor(SkillCatalogEntry.DEFAULT_USER_ID)

            assertEquals(1, outcome.pushed)
            assertEquals(0, outcome.unclaimed)
            assertEquals(SkillChecksums.dirDigest(file.parent), chain.catalog.allRows().single().checksum)
        }

        @Test
        fun `a personal root still claims directory-only skills`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft", owner = "alice")

            val outcome = chain.sync.syncFor("alice")

            assertEquals(1, outcome.claimed)
            assertEquals(0, outcome.unclaimed)
            assertEquals("alice", chain.catalog.allRows().single().userId)
        }
    }

    @Nested
    inner class AddAndDelete {
        @Test
        fun `add rejects hostile or empty names before any storage access`() = runTest {
            val chain = SkillSyncFixture(temp)
            val source = chain.staging("ok")
            source.resolve(SkillPaths.SKILL_FILE_NAME).writeText("---\nname: ok\ndescription: d\n---\nbody")

            assertTrue(chain.sync.addSkill("alice", "../escape", source) is SkillAddResult.Invalid)
            assertTrue(chain.sync.addSkill("alice", " ", source) is SkillAddResult.Invalid)
            assertTrue(chain.storage.objects.isEmpty())
            assertTrue(chain.catalog.allRows().isEmpty())
        }

        @Test
        fun `add over the size cap fails as Invalid and leaves nothing behind`() = runTest {
            val chain = SkillSyncFixture(temp, packageMaxBytes = 64L)
            val source = chain.staging("big")
            source.resolve(SkillPaths.SKILL_FILE_NAME).writeText("---\nname: big\ndescription: d\n---\n" + "x".repeat(4096))

            val result = chain.sync.addSkill("alice", "big", source)

            assertTrue(result is SkillAddResult.Invalid, "got: $result")
            assertTrue(Files.notExists(SkillPaths.ownerRoot(chain.config, "alice").resolve("big")))
            assertTrue(chain.storage.objects.isEmpty())
            assertTrue(chain.catalog.allRows().isEmpty())
        }

        @Test
        fun `an existing row turns the add into a NameConflict without touching storage`() = runTest {
            val chain = SkillSyncFixture(temp)
            val root = SkillPaths.ownerRoot(chain.config, "alice")
            val source = chain.staging("draft")
            source.resolve(SkillPaths.SKILL_FILE_NAME).writeText("---\nname: draft\ndescription: d\n---\nbody")
            val existing = chain.catalog.claim(
                SkillCatalogEntry(
                    name = "draft", checksum = "reserved",
                    rootPath = SkillPaths.canonicalize(root),
                    installPath = SkillPaths.canonicalize(SkillPaths.installDir(root, "draft")),
                    objectKey = chain.packages.keyFor("alice", "draft"),
                    userId = "alice"
                )
            )

            val result = chain.sync.addSkill("alice", "draft", source)

            assertTrue(result is SkillAddResult.NameConflict, "got: $result")
            assertEquals("reserved", chain.catalog.findByName("alice", "draft")?.checksum)
            assertNotNull(existing.id)
        }

        @Test
        fun `delete of an unknown skill removes nothing`() = runTest {
            val chain = SkillSyncFixture(temp)
            assertNull(chain.sync.deleteSkill("alice", "ghost"))
        }

        @Test
        fun `a folder upload is staged outside the root and then claimed`() = runTest {
            val chain = SkillSyncFixture(temp)
            val upload = SkillUpload.FromFiles(
                listOf(
                    SkillUploadFile("draft/SKILL.md", "---\nname: draft\ndescription: d\n---\nbody".toByteArray()),
                    SkillUploadFile("draft/reference.md", "reference".toByteArray())
                )
            )

            val result = chain.sync.addUploaded("alice", "draft", upload)

            val row = chain.catalog.findByName("alice", "draft")
            assertNotNull(row, "got: $result")
            val installed = Path.of(row.installPath)
            assertEquals(SkillPaths.canonicalize(SkillPaths.installDir(SkillPaths.ownerRoot(chain.config, "alice"), "draft")), row.installPath)
            assertTrue(Files.isRegularFile(installed.resolve("reference.md")))
            assertEquals(SkillChecksums.dirDigest(installed), row.checksum)
            assertNotNull(chain.storage.objects[row.objectKey])
            assertEquals(listOf("draft"), chain.registry.visibleFor("alice").map { it.name })
        }

        @Test
        fun `a zip upload installs the same tree`() = runTest {
            val chain = SkillSyncFixture(temp)
            val zip = ByteArrayOutputStream().also { buffer ->
                ZipOutputStream(buffer).use { out ->
                    out.putNextEntry(ZipEntry("SKILL.md"))
                    out.write("---\nname: draft\ndescription: d\n---\nbody".toByteArray())
                    out.closeEntry()
                }
            }.toByteArray()

            val result = chain.sync.addUploaded("alice", "draft", SkillUpload.FromZip(zip))

            assertNotNull(chain.catalog.findByName("alice", "draft"), "got: $result")
            assertEquals(listOf("draft"), chain.registry.visibleFor("alice").map { it.name })
        }

        @Test
        fun `an upload with a traversal path claims nothing`() = runTest {
            val chain = SkillSyncFixture(temp)
            val upload = SkillUpload.FromFiles(
                listOf(SkillUploadFile("../victim/SKILL.md", "---\nname: draft\ndescription: d\n---\nbody".toByteArray()))
            )

            assertTrue(chain.sync.addUploaded("alice", "draft", upload) is SkillAddResult.Invalid)

            assertTrue(chain.catalog.allRows().isEmpty())
            assertTrue(chain.storage.objects.isEmpty())
            assertTrue(Files.notExists(temp.resolve("victim")))
        }

        @Test
        fun `an upload without a skill definition file is refused`() = runTest {
            val chain = SkillSyncFixture(temp)
            val upload = SkillUpload.FromFiles(listOf(SkillUploadFile("draft/readme.md", "no skill here".toByteArray())))

            val result = chain.sync.addUploaded("alice", "draft", upload)

            assertTrue(result is SkillAddResult.Invalid, "got: $result")
            assertTrue(Files.notExists(SkillPaths.ownerRoot(chain.config, "alice").resolve("draft")))
        }

        @Test
        fun `a directory already inside the owner root is claimed in place`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")

            val result = chain.sync.addSkill("alice", "draft", file.parent)

            val row = chain.catalog.findByName("alice", "draft")
            assertNotNull(row, "got: $result")
            assertEquals(SkillPaths.canonicalize(file.parent), row.installPath)
            assertTrue(Files.isRegularFile(file), "an in-place claim must not delete the author's own files")
            assertNotNull(chain.storage.objects[row.objectKey])
        }
    }
}
