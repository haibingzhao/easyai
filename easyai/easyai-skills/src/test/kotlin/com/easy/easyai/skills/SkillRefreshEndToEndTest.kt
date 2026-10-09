package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillDeleteResult
import com.easy.easyai.core.skill.SkillDocumentState
import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.core.skill.SkillSubmitResult
import com.easy.easyai.core.skill.SkillSyncState
import com.easy.easyai.core.skill.SkillSyncUpdate
import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectMeta
import com.easy.easyai.core.storage.ObjectStorage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Full-pipeline tests over disk + catalog + package store + index store: the sync directions
 * (claim / restore / push) and the index projection must compose exactly as production wires them.
 */
class SkillRefreshEndToEndTest {
    @TempDir lateinit var temp: Path

    @Nested
    inner class Startup {
        @Test
        fun `unclaimed directories are claimed and confirmed for their owner root`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("private", owner = "alice")
            val result = chain.refresher.refreshFor("alice")
            val rows = chain.catalog.allRows()
            assertEquals(1, result.sync?.claimed)
            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals("alice", row.userId)
            assertEquals(SkillSyncState.SYNCED, row.syncState)
            assertEquals(row.checksum, row.indexedChecksum)
        }

        @Test
        fun `a hand-placed shared directory stays invisible until the system owner adds it`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("shared", owner = SkillModelFixture.SYSTEM)
            val access = SkillAccessResolver(chain.registry, chain.catalog)

            // Every request sweeps the shared root, so this pass used to turn the directory into a
            // skill the whole machine could load.
            chain.refresher.refreshFor("alice")

            assertTrue(chain.catalog.allRows().isEmpty(), "the shared root creates no rows from disk")
            assertTrue(
                access.listScopedSkills("alice").isEmpty(),
                "a registry entry without a catalog row must stay hidden"
            )

            val added = chain.refresher.addSkill(SkillModelFixture.SYSTEM, "shared", file.parent)
            assertTrue(added is SkillAddResult.Added, "got: $added")
            chain.refresher.refreshFor("alice")

            val visible = access.listScopedSkills("alice")
            assertEquals(listOf("shared"), visible.map { it.skill.name })
            assertTrue(visible.single().shared)
            assertEquals(SkillSyncState.SYNCED, visible.single().catalogEntry!!.syncState)
        }

        @Test
        fun `a personal root is reconciled only for its own owner`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("private", owner = "alice")
            chain.refresher.refreshFor("bob")
            assertTrue(chain.catalog.allRows().isEmpty())
            val result = chain.refresher.refreshFor("alice")
            assertEquals(1, result.sync?.claimed)
            assertEquals("alice", chain.catalog.allRows().single().userId)
        }

        @Test
        fun `private disabled row survives a new registry and startup without a system copy`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("private", owner = "alice")
            chain.refresher.refreshFor("alice")
            val row = chain.catalog.allRows().single()
            chain.management.setEnabled("private", SkillOwnerContext("alice"), false)
            val registry = DefaultSkillRegistry(DefaultSkillDiscovery(), chain.config)
            val sync = SkillSyncService(chain.catalog, registry, DefaultSkillDiscovery(), chain.packages, chain.config)
            val indexer = SkillIndexer(chain.remote, chain.catalog, sync)
            SkillRefreshService(sync, indexer).refreshFor("alice")
            val remaining = chain.catalog.allRows().single()
            assertEquals(row.id, remaining.id)
            assertEquals("alice", remaining.userId)
            assertFalse(remaining.enabled)
            assertEquals(SkillSyncState.ABSENT, remaining.syncState)
            assertTrue(chain.catalog.listByUser("system").isEmpty())
        }
    }

    @Nested
    inner class SyncDirections {
        @Test
        fun `deleted package cannot be restored and the row is skipped`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.refresher.refreshFor("alice")
            val row = chain.catalog.allRows().single()
            SkillPackages.deleteTree(file.parent)
            chain.storage.objects.clear()

            val result = chain.sync.syncFor("alice")
            assertEquals(1, result.skipped)
            assertTrue(Files.notExists(file.parent))
            assertNotNull(chain.catalog.findByName("alice", "draft"))
        }

        @Test
        fun `restore recreates a missing directory from its zip without touching the row`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.refresher.refreshFor("alice")
            val row = chain.catalog.allRows().single()
            SkillPackages.deleteTree(file.parent)
            file.parent.deleteIfExists()

            val result = chain.sync.syncFor("alice")
            assertEquals(1, result.restored)
            assertTrue(Files.isRegularFile(row.installPath.let { Path.of(it) }.resolve(SkillPaths.SKILL_FILE_NAME)))
            val digested = SkillChecksums.dirDigest(Path.of(row.installPath))
            assertEquals(row.checksum, digested)
        }

        @Test
        fun `restore refuses to replace a directory whose SKILL md cannot be read`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.refresher.refreshFor("alice")
            // Discovery only reports parsable skills, so this directory looks missing to the sync
            // pass — but it holds the user's own edit, which a restore must never overwrite.
            file.writeText("---\ndescription: no name left\n---\nlocal edit")

            val result = chain.sync.syncFor("alice")

            assertEquals(1, result.skipped)
            assertEquals(0, result.restored)
            assertTrue(file.readText().contains("local edit"))
        }

        @Test
        fun `a missing directory keeps the row enabled while its package can still restore it`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.refresher.refreshFor("alice")
            SkillPackages.deleteTree(file.parent)

            val summary = chain.indexer.reconcileByDrift(listOf("alice"))

            val row = chain.catalog.allRows().single()
            assertEquals(1, summary.failed)
            assertTrue(row.enabled, "only the user turns a skill off")
            assertEquals(SkillSyncState.PENDING_INDEX, row.syncState)
            assertNotNull(row.lastError)
        }

        @Test
        fun `local content edit is pushed back into package and catalog`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.refresher.refreshFor("alice")
            val before = chain.catalog.allRows().single()
            val bytes = chain.storage.objects.getValue(before.objectKey).bytes.copyOf()

            val stamp = Files.getLastModifiedTime(file)
            file.writeText("---\nname: draft\ndescription: d\nversion: 1.0.0\n---\nnew body")
            Files.setLastModifiedTime(file, stamp)
            val outcome = chain.refresher.refreshFor("alice")
            val row = chain.catalog.allRows().single()
            assertEquals(1, outcome.sync?.pushed)
            // The sync pass consumed the drift, so the index pass reports no content update of its own.
            assertEquals(0, outcome.summary?.updated)
            assertEquals(SkillChecksums.dirDigest(file.parent), row.checksum)
            assertFalse(chain.storage.objects.getValue(row.objectKey).bytes.contentEquals(bytes))
            assertEquals("new body", chain.remote.documents.values.single().content.trim())

            assertEquals(SkillSyncState.SYNCED, row.syncState)
        }

        @Test
        fun `add validates rewrites the name and claims the row`() = runTest {
            val chain = SkillSyncFixture(temp)
            val source = chain.staging("other")
            source.resolve("SKILL.md").writeText("---\nname: other\ndescription: d\n---\nbody")

            val added = chain.refresher.addSkill("alice", "draft", source)
            assertTrue(added is SkillAddResult.Added, "got: $added")
            val row = (added as SkillAddResult.Added).row
            assertEquals("draft", row.name)
            assertEquals(SkillPaths.canonicalize(SkillPaths.installDir(SkillPaths.ownerRoot(chain.config, "alice"), "draft")), row.installPath)
            assertEquals("draft", SkillLoader.parse(Path.of(row.installPath, SkillPaths.SKILL_FILE_NAME)).name)
            assertEquals(1, chain.catalog.allRows().size)
            assertTrue(chain.storage.objects.containsKey(row.objectKey))

            val conflict = chain.refresher.addSkill("alice", "draft", source)
            assertTrue(conflict is SkillAddResult.NameConflict, "got: $conflict")
        }

        @Test
        fun `add refuses an invalid source without leaving a directory behind`() = runTest {
            val chain = SkillSyncFixture(temp)
            val source = chain.staging("broken")
            source.resolve("SKILL.md").writeText("no frontmatter at all")

            val result = chain.refresher.addSkill("alice", "draft", source)
            assertTrue(result is SkillAddResult.Invalid, "got: $result")
            assertTrue(Files.notExists(SkillPaths.ownerRoot(chain.config, "alice").resolve("draft")))
            assertTrue(chain.catalog.allRows().isEmpty())
        }

        @Test
        fun `delete removes row package directory and index document`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.refresher.refreshFor("alice")
            val row = chain.catalog.allRows().single()

            val removed = chain.refresher.deleteSkill("alice", "draft")
            assertEquals(row.id, removed?.id)
            assertNull(chain.catalog.findByName("alice", "draft"))
            assertFalse(chain.storage.objects.containsKey(row.objectKey))
            assertTrue(Files.notExists(file.parent))
            assertTrue(chain.remote.documents.isEmpty())
            assertNull(chain.registry.get("alice", "draft"))
        }
    }

    @Nested
    inner class Recovery {
        @Test
        fun `claim failure heals on refresh even when nothing new registers`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft", owner = "alice")
            chain.catalog.failClaims = true
            assertEquals(1, chain.refresher.refreshFor("alice").sync?.failed)
            assertTrue(chain.catalog.allRows().isEmpty())
            chain.catalog.failClaims = false
            val second = chain.refresher.refreshFor("alice")
            assertEquals(1, second.sync?.claimed)
            assertEquals(1, second.summary?.confirmed)
        }

        @Test
        fun `no rag still publishes content and retains pending work`() = runTest {
            val chain = SkillSyncFixture(temp, withRemote = false)
            val file = chain.write("draft", owner = "alice", body = "old")
            chain.refresher.refreshFor("alice")
            file.writeText("---\nname: draft\ndescription: d\nversion: 1.0.0\n---\nnew")
            val second = chain.refresher.refreshFor("alice")
            val row = chain.catalog.allRows().single()
            assertEquals("new", chain.registry.get("alice", "draft")?.content?.trim())
            assertEquals(SkillSyncState.PENDING_INDEX, row.syncState)
            assertNull(row.indexedChecksum)
            assertEquals(1, second.summary?.pending)
        }

        @Test
        fun `lazy first-access sync then request paths read the claimed row`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft", owner = "alice")
            // Authoring paths (login hook / refresh) thread `self`, so they claim the dropped
            // directory. The prompt gate below is read-warm and restore-only: it mirrors what the
            // catalog already holds but never claims a fresh orphan for the caller's own bucket.
            chain.refresher.ensureSynced("alice")
            val prompt = SkillPromptSource(chain.registry, chain.catalog, true, false, firstAccessSync = chain.refresher::ensureSyncedOwners)
            assertEquals(listOf("draft"), prompt.effectiveNames("alice"))
            // The gate already ran for this process, so a later write needs an explicit refresh.
            chain.write("later", owner = "alice")
            assertEquals(listOf("draft"), prompt.effectiveNames("alice"))
            chain.refresher.refreshFor("alice")
            assertEquals(listOf("draft", "later"), prompt.effectiveNames("alice"))
        }
    }
}

/** Stateful fakes retain revisions so these tests exercise real coordination, not canned mock answers. */
internal class SkillSyncFixture(val temp: Path, withRemote: Boolean = true, packageMaxBytes: Long = 20L * 1024 * 1024) {
    val config = SkillConfig(
        rootDir = temp.resolve("skills-root").createDirectories().toString(),
        packageMaxBytes = packageMaxBytes
    )
    val registry = DefaultSkillRegistry(DefaultSkillDiscovery(), config)
    val catalog = TestSkillCatalog()
    val remote = TestSkillStore()
    val storage = TestObjectStorage()
    val packages = SkillPackageStore(null, storage)
    val sync = SkillSyncService(catalog, registry, DefaultSkillDiscovery(), packages, config)
    val indexer = SkillIndexer(if (withRemote) remote else null, catalog, sync)
    val refresher = SkillRefreshService(sync, indexer)
    val management = SkillCatalogService(catalog, indexer)

    /** Write an unclaimed `{rootDir}/{owner}/{name}/SKILL.md` and return its path. */
    fun write(name: String, owner: String, body: String = "body"): Path {
        val file = SkillPaths.installDir(SkillPaths.ownerRoot(config, owner), name)
            .createDirectories().resolve(SkillPaths.SKILL_FILE_NAME)
        file.writeText("---\nname: $name\ndescription: d\nversion: 1.0.0\n---\n$body")
        return file
    }

    /** A staging directory outside the skill root, as an upload or server-side pick would produce. */
    fun staging(name: String): Path = temp.resolve("staging-$name").createDirectories()
}

internal class TestSkillCatalog : AsyncSkillCatalogStore {
    private val rows = LinkedHashMap<String, SkillCatalogEntry>()
    var failClaims = false
    var failContentUpdates = false

    fun allRows(): List<SkillCatalogEntry> = synchronized(rows) { rows.values.toList() }

    /** Overwrite a row wholesale, simulating an external edit for path/identity tests. */
    fun replace(row: SkillCatalogEntry) = synchronized(rows) { rows[row.id] = row }

    override suspend fun claim(entry: SkillCatalogEntry): SkillCatalogEntry = synchronized(rows) {
        check(!failClaims) { "catalog write unavailable" }
        val existing = rows.values.firstOrNull { it.userId == entry.userId && it.name == entry.name }
        existing ?: entry.copy(id = entry.id.ifBlank { UUID.randomUUID().toString() }).also { rows[it.id] = it }
    }

    override suspend fun findById(id: String): SkillCatalogEntry? = synchronized(rows) { rows[id] }

    override suspend fun findByName(userId: String, name: String): SkillCatalogEntry? =
        synchronized(rows) { rows.values.firstOrNull { it.userId == userId && it.name == name } }

    override suspend fun listByUser(userId: String): List<SkillCatalogEntry> =
        synchronized(rows) { rows.values.filter { it.userId == userId } }

    override suspend fun listByOwners(userIds: List<String>): List<SkillCatalogEntry> = synchronized(rows) {
        userIds.flatMap { owner -> rows.values.filter { it.userId == owner } }
    }

    override suspend fun listDistinctUserIds(): List<String> =
        synchronized(rows) { rows.values.map { it.userId }.distinct() }

    override suspend fun setEnabled(id: String, enabled: Boolean): Boolean = synchronized(rows) {
        val row = rows[id] ?: return@synchronized false
        rows[id] = row.copy(
            enabled = enabled, revision = row.revision + 1, nextAttemptAt = null, lastError = null,
            syncState = if (enabled) SkillSyncState.PENDING_INDEX else SkillSyncState.PENDING_DELETE
        )
        true
    }

    override suspend fun updateContent(
        id: String, expectedRevision: Long, checksum: String, version: String, enable: Boolean
    ): Boolean = synchronized(rows) {
        val row = rows[id]?.takeIf { it.revision == expectedRevision } ?: return@synchronized false
        if (failContentUpdates) return@synchronized false
        rows[id] = row.copy(
            checksum = checksum, version = version, enabled = row.enabled || enable,
            revision = row.revision + 1, nextAttemptAt = null, lastError = null,
            syncState = if (row.enabled || enable) SkillSyncState.PENDING_INDEX else SkillSyncState.PENDING_DELETE
        )
        true
    }

    override suspend fun updateSync(id: String, expectedRevision: Long, update: SkillSyncUpdate): Boolean = synchronized(rows) {
        val row = rows[id]?.takeIf { it.revision == expectedRevision } ?: return@synchronized false
        if (update.state == SkillSyncState.SYNCED && (!row.enabled || update.indexedChecksum != row.checksum)) return@synchronized false
        rows[id] = row.copy(
            syncState = update.state, indexedChecksum = update.indexedChecksum,
            nextAttemptAt = update.nextAttemptAt, lastError = update.lastError, revision = row.revision + 1
        )
        true
    }

    override suspend fun delete(id: String): Boolean = synchronized(rows) { rows.remove(id) != null }
}

internal class TestObjectStorage : ObjectStorage {
    val objects = LinkedHashMap<String, ObjectContent>()
    var failPut = false
    var failList = false
    val listedPrefixes = mutableListOf<String>()
    var headCalls = 0

    override suspend fun head(key: String): ObjectMeta? {
        headCalls++
        return objects[key]?.meta
    }

    override suspend fun listKeys(prefix: String): Set<String> {
        check(!failList) { "listing unavailable" }
        listedPrefixes += prefix
        return objects.keys.filter { it.startsWith(prefix) }.toSet()
    }

    override suspend fun get(key: String): ObjectContent? = objects[key]
    override suspend fun put(key: String, bytes: ByteArray, contentType: String): ObjectMeta {
        check(!failPut) { "storage unavailable" }
        val meta = ObjectMeta(key, bytes.size.toLong())
        objects[key] = ObjectContent(meta, bytes)
        return meta
    }

    override suspend fun delete(key: String): Boolean = objects.remove(key) != null
    override suspend fun presignedGetUrl(key: String, ttlSeconds: Long): String? = null
}

internal class TestSkillStore : SkillStore {
    val documents = LinkedHashMap<String, SkillEntry>()
    val states = LinkedHashMap<String, SkillDocumentState>()
    val submitted = mutableListOf<SkillEntry>()
    val deleted = mutableListOf<String>()
    var asynchronous = false
    var failSubmit = false
    var failDelete = false
    var onSubmit: suspend (SkillEntry) -> Unit = {}

    private fun address(owner: String, name: String) = "$owner:$name"

    override suspend fun submit(
        entries: List<SkillEntry>, owner: SkillOwnerContext, awaitIndexing: Boolean
    ): List<SkillSubmitResult> = entries.map { entry ->
        submitted.add(entry)
        onSubmit(entry)
        val state = when {
            failSubmit -> SkillDocumentState.Failed("remote unavailable")
            asynchronous -> SkillDocumentState.Submitted(entry.checksum)
            else -> SkillDocumentState.Processed(entry.checksum)
        }
        if (!failSubmit) {
            val key = address(owner.userId ?: SkillCatalogEntry.DEFAULT_USER_ID, entry.name)
            documents[key] = entry
            states[key] = state
        }
        SkillSubmitResult(entry.key, state)
    }

    override suspend fun inspect(name: String, owner: SkillOwnerContext): SkillDocumentState =
        states[address(owner.userId ?: SkillCatalogEntry.DEFAULT_USER_ID, name)] ?: SkillDocumentState.Absent

    override suspend fun ensureAbsent(name: String, owner: SkillOwnerContext): SkillDeleteResult {
        if (failDelete) return SkillDeleteResult.Failed("delete unavailable")
        val key = address(owner.userId ?: SkillCatalogEntry.DEFAULT_USER_ID, name)
        deleted.add(key)
        states.remove(key)
        documents.remove(key)
        return SkillDeleteResult.Absent
    }

    override suspend fun search(query: String, ownerUserIds: List<String>, topK: Int): List<SkillEntry> =
        documents.filter { (key, doc) -> ownerUserIds.any { key.startsWith("$it:") } && doc.name == query }.values.take(topK)
}
