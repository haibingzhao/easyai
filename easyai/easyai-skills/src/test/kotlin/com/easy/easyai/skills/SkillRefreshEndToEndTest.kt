package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillDeleteResult
import com.easy.easyai.core.skill.SkillDocumentState
import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.core.skill.SkillSubmitResult
import com.easy.easyai.core.skill.SkillSyncState
import com.easy.easyai.core.skill.SkillSyncUpdate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillRefreshEndToEndTest {
    @TempDir lateinit var temp: Path

    @Nested
    inner class Startup {
        @Test
        fun `cold startup claims global bytes pending and confirms the remote version`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("shared", global = true)
            val result = assertNotNull(chain.refresher.reconcileAllOwners())
            val row = chain.catalog.listAll().single()
            assertEquals("system", row.userId)
            assertEquals(SkillSyncState.SYNCED, row.syncState)
            assertEquals(row.checksum, row.indexedChecksum)
            assertEquals(1, result.submitted)
            assertEquals(1, result.confirmed)
        }

        @Test
        fun `startup never claims unowned project sources as system`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("private")
            chain.registry.rescan(setOf(chain.project))
            chain.refresher.reconcileAllOwners()
            assertTrue(chain.catalog.listAll().isEmpty())
            val result = chain.refresher.refreshFor("alice", chain.project)
            assertEquals(1, result.claimed)
            assertEquals("alice", chain.catalog.listAll().single().userId)
        }

        @Test
        fun `private disabled row survives a new registry and startup without a system copy`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("private")
            chain.refresher.refreshFor("alice", chain.project)
            val row = chain.catalog.listAll().single()
            chain.catalog.setEnabled(row.id, false)
            val registry = DefaultSkillRegistry(DefaultSkillDiscovery(), chain.config)
            val sync = SkillCatalogSyncService(chain.catalog, chain.config, registry)
            val indexer = SkillIndexer(chain.remote, chain.catalog, sync, chain.config)
            SkillRefreshService(registry, chain.catalog, sync, indexer, config = chain.config).reconcileAllOwners()
            val remaining = chain.catalog.listAll().single()
            assertEquals(row.id, remaining.id)
            assertEquals("alice", remaining.userId)
            assertFalse(remaining.enabled)
            assertEquals(SkillSyncState.ABSENT, remaining.syncState)
            assertTrue(chain.catalog.listByUser("system").isEmpty())
        }
    }

    @Nested
    inner class Recovery {
        @Test
        fun `claim failure heals on refresh even when addedKeys is empty`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft")
            chain.catalog.failClaims = true
            assertEquals(1, chain.refresher.refreshFor("alice", chain.project).claimFailed)
            chain.catalog.failClaims = false
            val second = chain.refresher.refreshFor("alice", chain.project)
            assertTrue(second.delta.addedKeys.isEmpty())
            assertEquals(1, second.claimed)
            assertEquals(1, second.summary?.confirmed)
        }

        @Test
        fun `no rag still publishes observed content and retains pending work`() = runTest {
            val chain = SkillSyncFixture(temp, withRemote = false)
            val file = chain.write("draft", "old")
            chain.refresher.refreshFor("alice", chain.project)
            file.writeText("---\nname: draft\ndescription: d\n---\nnew")
            val second = chain.refresher.refreshFor("alice", chain.project)
            val row = chain.catalog.listAll().single()
            assertEquals("new", chain.registry.get("draft", chain.project)?.content)
            assertEquals(SkillSyncState.PENDING_INDEX, row.syncState)
            assertNull(row.indexedChecksum)
            assertEquals(1, second.summary?.pending)
        }
    }
}

/** Stateful fakes retain revisions so these tests exercise real coordination, not canned mock answers. */
internal class SkillSyncFixture(temp: Path, withRemote: Boolean = true) {
    val project = temp.resolve("project").createDirectories()
    val globalRoot = temp.resolve("shared").createDirectories()
    val config = SkillConfig(
        paths = listOf(globalRoot.toString()), homeSkillDirs = listOf("skill-test-${UUID.randomUUID()}"),
        workDir = temp.resolve("server").createDirectories().toString()
    )
    val registry = DefaultSkillRegistry(DefaultSkillDiscovery(), config)
    val catalog = TestSkillCatalog()
    val remote = TestSkillStore()
    val sync = SkillCatalogSyncService(catalog, config, registry)
    val indexer = SkillIndexer(if (withRemote) remote else null, catalog, sync, config)
    val refresher = SkillRefreshService(registry, catalog, sync, indexer, config = config)
    val management = SkillCatalogService(catalog, indexer, if (withRemote) remote else null, config)

    fun write(name: String, body: String = "body", global: Boolean = false): Path {
        val root = if (global) globalRoot else project.resolve(config.homeSkillDirs.single())
        val file = root.resolve(name).createDirectories().resolve("SKILL.md")
        file.writeText("---\nname: $name\ndescription: d\nversion: 1.0.0\n---\n$body")
        return file
    }
}

internal class TestSkillCatalog : AsyncSkillCatalogStore {
    private val rows = LinkedHashMap<String, SkillCatalogEntry>()
    var failLists = false
    var failClaims = false

    override suspend fun claim(entry: SkillCatalogEntry): SkillCatalogEntry = synchronized(rows) {
        check(!failClaims) { "catalog write unavailable" }
        val existing = rows.values.firstOrNull {
            it.userId == entry.userId && it.name == entry.name && it.projectHash == entry.projectHash
        }
        existing ?: entry.copy(id = entry.id.ifBlank { UUID.randomUUID().toString() }).also { rows[it.id] = it }
    }

    override suspend fun findById(id: String): SkillCatalogEntry? = synchronized(rows) { rows[id] }
    override suspend fun listAll(): List<SkillCatalogEntry> = synchronized(rows) {
        check(!failLists) { "catalog read unavailable" }
        rows.values.toList()
    }
    override suspend fun listByUser(userId: String) = listAll().filter { it.userId == userId }
    override suspend fun listByName(name: String, userId: String) = listByUser(userId).filter { it.name == name }
    override suspend fun listDistinctUserIds() = listAll().map { it.userId }.distinct()

    override suspend fun setEnabled(id: String, enabled: Boolean): Boolean = synchronized(rows) {
        val row = rows[id] ?: return@synchronized false
        rows[id] = row.copy(enabled = enabled, revision = row.revision + 1, nextAttemptAt = null, lastError = null,
            syncState = if (enabled) SkillSyncState.PENDING_INDEX else SkillSyncState.PENDING_DELETE)
        true
    }

    override suspend fun updateContent(
        id: String, expectedRevision: Long, checksum: String, version: String, enable: Boolean
    ): Boolean = synchronized(rows) {
        val row = rows[id]?.takeIf { it.revision == expectedRevision } ?: return@synchronized false
        rows[id] = row.copy(checksum = checksum, version = version, enabled = row.enabled || enable,
            revision = row.revision + 1, nextAttemptAt = null, lastError = null,
            syncState = if (row.enabled || enable) SkillSyncState.PENDING_INDEX else SkillSyncState.PENDING_DELETE)
        true
    }

    override suspend fun updateSync(id: String, expectedRevision: Long, update: SkillSyncUpdate): Boolean = synchronized(rows) {
        val row = rows[id]?.takeIf { it.revision == expectedRevision } ?: return@synchronized false
        if (update.state == SkillSyncState.SYNCED && (!row.enabled || update.indexedChecksum != row.checksum)) return@synchronized false
        rows[id] = row.copy(syncState = update.state, indexedChecksum = update.indexedChecksum,
            nextAttemptAt = update.nextAttemptAt, lastError = update.lastError, revision = row.revision + 1)
        true
    }

    override suspend fun delete(id: String): Boolean = synchronized(rows) { rows.remove(id) != null }
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

    fun address(name: String, scope: SkillScope, owner: SkillOwnerContext): String =
        "${owner.userId}:$scope:${owner.projectPath}:$name"

    override suspend fun submit(
        entries: List<SkillEntry>, scope: SkillScope, owner: SkillOwnerContext, awaitIndexing: Boolean
    ): List<SkillSubmitResult> = entries.map { entry ->
        submitted.add(entry)
        onSubmit(entry)
        val state = when {
            failSubmit -> SkillDocumentState.Failed("remote unavailable")
            asynchronous -> SkillDocumentState.Submitted(entry.checksum)
            else -> SkillDocumentState.Processed(entry.checksum)
        }
        if (!failSubmit) {
            val key = address(entry.name, scope, owner)
            documents[key] = entry
            states[key] = state
        }
        SkillSubmitResult(entry.key, state)
    }

    override suspend fun inspect(name: String, scope: SkillScope, owner: SkillOwnerContext): SkillDocumentState =
        states[address(name, scope, owner)] ?: SkillDocumentState.Absent

    override suspend fun ensureAbsent(name: String, scope: SkillScope, owner: SkillOwnerContext): SkillDeleteResult {
        if (failDelete) return SkillDeleteResult.Failed("delete unavailable")
        val key = address(name, scope, owner)
        deleted.add(key)
        states.remove(key)
        documents.remove(key)
        return SkillDeleteResult.Absent
    }

    override suspend fun search(query: String, scopes: List<SkillScope>, owner: SkillOwnerContext, topK: Int): List<SkillEntry> =
        documents.filter { (key, doc) -> scopes.any { address(doc.name, it, owner) == key } }.values.take(topK)
}
