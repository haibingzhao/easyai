package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the published chain end to end: a SKILL.md appears on disk, `refresh_skills` is called, and the
 * result is a skill that `load_skill` serves and `skill_search` could find.
 *
 * Everything real except the two databases — [DefaultSkillRegistry], [SkillCatalogSyncService],
 * [SkillIndexer], [SkillPromptSource], [RefreshSkillsTool] and [SkillTool] are the production objects,
 * while the catalog table and the retrieval backend are replaced by the in-memory fakes below. That is
 * the point: the unit tests wire mocks together and can only assert that one collaborator was called,
 * whereas the failure modes of this feature live *between* the stages — a row claimed under a tenant
 * the load gate will not resolve, an index write that never happens because the new row's own checksum
 * makes drift look unchanged, a project directory the server never walks up to. Those only show up when
 * the stages actually hand state to each other.
 *
 * Mirrors the documented smoke sequence (`write` → `refresh_skills` → `load_skill` → the skill appears
 * in the catalog listing) without needing R2DBC, EasyRAG or a model.
 *
 * Runs on [runBlocking] rather than `runTest` on purpose: the chain does real file IO on `Dispatchers.IO`
 * and the index stage must be exercised the way production exercises it, not against a virtual clock.
 */
class SkillRefreshEndToEndTest {

    private val discovery = DefaultSkillDiscovery()

    private val refreshMetadata = ToolMetadata(
        name = "refresh_skills",
        description = "Make skills just written with `write` usable",
        permissionCategory = "skill",
        isDefaultTool = false,
        alwaysInclude = true
    )

    private val loadMetadata = ToolMetadata(
        name = "load_skill",
        description = "Load a skill",
        permissionCategory = "skill",
        isDefaultTool = true
    )

    /**
     * One isolated deployment: a global skill root, a server started outside the project, and a project
     * whose skill directory the server has no reason to know about.
     */
    private inner class Chain(tempDir: Path) {
        val globalRoot = tempDir.resolve("global-skills").createDirectories()
        val project = tempDir.resolve("repo").createDirectories()

        val config = SkillConfig(
            enabled = true,
            paths = listOf(globalRoot.toString()),
            // A directory name that cannot exist under a developer's home, so the ancestor walk stays
            // inside the temp tree instead of picking up the real `~/.easyai/skills`.
            homeSkillDirs = listOf("nested-skills"),
            workDir = tempDir.resolve("server").createDirectories().toString()
        )

        val registry = DefaultSkillRegistry(discovery, config)
        val catalog = MemoryCatalog()
        val store = MemoryStore()
        val syncService = SkillCatalogSyncService(catalog, config)
        val indexer = SkillIndexer(store, catalog, syncService, config)
        // Last stage of the production sequence, so the chain under test is the one that ships; what the
        // prompt view itself does with the rows it re-reads is [SkillPromptSourceTest]'s business.
        val promptSource = SkillPromptSource(
            registry, catalog,
            injectIntoSystemPrompt = true,
            ragEnabled = true,
            ragDiscoveryReady = true
        )
        val refresher = SkillRefreshService(registry, catalog, syncService, indexer, promptSource, config)

        init {
            // H5 made the registry's first scan lazy: production triggers it via the startup runner or
            // the first prompt render, whichever lands first. The chain under test has to do it
            // explicitly so a skill seeded on disk *before* the chain was built counts as part of the
            // starting snapshot rather than as churn on the first refresh.
            registry.all()
        }

        fun writeSkillUnder(root: Path, name: String, body: String): Path {
            val dir = root.resolve("nested-skills").resolve(name).createDirectories()
            dir.resolve("SKILL.md").writeText("---\nname: $name\ndescription: Builds $name reports\n---\n$body")
            return dir
        }

        fun writeProjectSkill(name: String, body: String = "Step 1: do the thing."): Path =
            writeSkillUnder(project, name, body)

        suspend fun refresh(
            scope: CoroutineScope,
            userId: String?,
            allowed: List<String> = emptyList(),
            path: Path = project
        ): String {
            val tool = RefreshSkillsTool(refreshMetadata, registry, refresher, allowed)
            val result = tool.execute(
                agentContext = context(userId, path),
                toolCallId = "tc-refresh",
                args = mapOf("note" to "after writing a skill"),
                coroutineScope = scope,
                onUpdate = {}
            )
            assertFalse(result.isError, "a refresh must never fail the loop: ${result.text()}")
            return result.text()
        }

        suspend fun load(
            scope: CoroutineScope,
            name: String,
            userId: String?,
            allowed: List<String>,
            path: Path = project,
            targetRegistry: SkillRegistry = registry
        ): ToolResult =
            SkillTool(loadMetadata, targetRegistry, allowed, catalog, config).execute(
                agentContext = context(userId, path),
                toolCallId = "tc-load",
                args = mapOf("name" to name),
                coroutineScope = scope,
                onUpdate = {}
            )

        private fun context(userId: String?, path: Path = project) =
            AgentContext(agentId = "a", userId = userId, projectPath = path)
    }

    private fun ToolResult.text(): String = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    @Test
    fun `one refresh turns a written file into a loadable, searchable skill`(@TempDir tempDir: Path) = runBlocking {
        val chain = Chain(tempDir)
        val skillDir = chain.writeProjectSkill("pdf-report")
        assertNull(chain.registry.get("pdf-report", chain.project), "bytes on disk are not a skill until something re-reads them")

        val answer = chain.refresh(this, userId = "alice", allowed = listOf("pdf-report"))

        assertTrue("added=[pdf-report]" in answer, "the agent cannot tell whether its own file made it: $answer")
        // The registry root the server walks up to is the project of the *request*, not of the process.
        assertEquals(skillDir, chain.registry.get("pdf-report", chain.project)?.location?.parent)

        // A requester with no rows of its own is served by the shared tenant, so that is where the row
        // has to land — `load_skill` below resolves exactly that tenant and would refuse otherwise.
        val row = chain.catalog.listByName("pdf-report", SkillCatalogEntry.DEFAULT_USER_ID).single()
        assertEquals(skillDir.toString(), row.installPath, "the row must point at the directory the loader reads")
        assertEquals(SkillCatalogEntry.SOURCE_LOCAL, row.source)
        assertEquals(
            SkillScopeResolver.projectHashOf(chain.project), row.projectHash,
            "a PROJECT row must carry its project's granularity token or a same-named skill of another project would collide"
        )
        assertTrue(
            chain.catalog.listByName("pdf-report", "alice").isEmpty(),
            "claiming for the requester would hide the skill from every other identity on this box"
        )
        assertTrue(
            chain.catalog.listByUser(SkillCatalogEntry.DEFAULT_USER_ID).any { it.name == "pdf-report" },
            "the management listing reads the catalog, so a missing row means an invisible skill"
        )

        // Indexed into the slice the owner would search, and handed over without waiting for the backend:
        // a refresh inside a conversation turn may not block on embedding, so the tool says "submitted".
        val hits = chain.store.search(
            "pdf-report",
            scopes = listOf(SkillScope.PROJECT),
            owner = SkillOwnerContext(SkillCatalogEntry.DEFAULT_USER_ID, chain.project)
        )
        assertEquals(listOf("pdf-report"), hits.map { it.name }, "skill_search could not have offered it")
        assertFalse(chain.store.awaited, "a refresh must never wait on the index backend")
        assertTrue("handed 1 to the search index" in answer, "the answer must not promise finished embedding: $answer")

        val loaded = chain.load(this, "pdf-report", userId = "alice", allowed = listOf("pdf-report"))
        assertFalse(loaded.isError, "the whole point of the chain: ${loaded.text()}")
        assertTrue("Step 1: do the thing." in loaded.text(), "the body comes off disk, not out of the index")
    }

    @Test
    fun `a second refresh leaves the snapshot and the index untouched`(@TempDir tempDir: Path) = runBlocking {
        val chain = Chain(tempDir)
        chain.writeProjectSkill("pdf-report")
        chain.refresh(this, userId = "alice")
        val writesAfterFirst = chain.store.writes

        val answer = chain.refresh(this, userId = "alice")

        // Ends on a Unit-returning assertion: an expression-body test whose last expression has a value
        // compiles to a non-void method, which the Jupiter engine silently refuses to run.
        assertEquals(writesAfterFirst, chain.store.writes, "content that did not move must not be re-pushed")
        assertNotNull(chain.registry.get("pdf-report", chain.project), "the snapshot must survive a steady-state pass")
        assertTrue("added=[] removed=[]" in answer, "a re-read of an unchanged disk must not look like churn: $answer")
    }

    @Test
    fun `a requester that already owns rows keeps its new skill inside its own tenant`(@TempDir tempDir: Path) = runBlocking {
        // Seeded before the registry is built, so it is part of the starting snapshot rather than of the delta.
        val legacyDir = tempDir.resolve("global-skills/legacy").createDirectories()
        legacyDir.resolve("SKILL.md").writeText("---\nname: legacy\ndescription: Legacy report tool\n---\nOld body")
        val chain = Chain(tempDir)
        chain.catalog.upsert(
            SkillCatalogEntry(
                name = "legacy",
                checksum = assertNotNull(chain.syncService.checksumOf(legacyDir)),
                installPath = legacyDir.toString(),
                userId = "carol"
            )
        )
        chain.writeProjectSkill("pdf-report")

        val answer = chain.refresh(this, userId = "carol", allowed = listOf("pdf-report"))

        assertTrue("added=[pdf-report]" in answer, "the requester's existing skill is not new: $answer")
        assertTrue("owner 'carol'" in answer, "carol has rows, so her new skill is hers: $answer")
        assertEquals(1, chain.catalog.listByName("pdf-report", "carol").size)
        assertTrue(
            chain.catalog.listByName("pdf-report", SkillCatalogEntry.DEFAULT_USER_ID).isEmpty(),
            "claiming a private skill into the shared tenant would expose it to every other user"
        )
        val loaded = chain.load(this, "pdf-report", userId = "carol", allowed = listOf("pdf-report"))
        assertFalse(
            loaded.isError,
            "the row and the load gate resolve to the same tenant, or a refresh is useless to its author: ${loaded.text()}"
        )
        val refused = chain.load(this, "pdf-report", userId = "bob", allowed = listOf("pdf-report"))
        assertTrue(refused.isError, "another identity must not reach a private tenant's files")
        assertTrue("not installed for this user" in refused.text().lowercase(), "got: ${refused.text()}")
    }

    @Test
    fun `a frontmatter the loader rejects is reported as not registered`(@TempDir tempDir: Path) = runBlocking {
        val chain = Chain(tempDir)
        val dir = chain.project.resolve("nested-skills").resolve("broken").createDirectories()
        dir.resolve("SKILL.md").writeText("---\ndescription: no name at all\n---\nBody")

        val answer = chain.refresh(this, userId = "alice")

        assertTrue("added=[]" in answer, "a file that cannot be parsed cannot be registered: $answer")
        assertTrue(
            "was not parsed" in answer && "Failed to parse SKILL.md at" in answer,
            "the agent needs the next action, not just a zero: $answer"
        )
        assertTrue(chain.catalog.listByName("broken", SkillCatalogEntry.DEFAULT_USER_ID).isEmpty())
        assertEquals(0, chain.store.writes, "nothing parseable means nothing to push")
    }

    @Test
    fun `two projects carrying the same skill name each serve their own bytes`(@TempDir tempDir: Path) = runBlocking {
        val chain = Chain(tempDir)
        val dirA = chain.writeProjectSkill("pdf-report", body = "Step 1: from project A.")
        val projectB = tempDir.resolve("repo-b").createDirectories()
        val dirB = chain.writeSkillUnder(projectB, "pdf-report", body = "Step 1: from project B.")

        chain.refresh(this, userId = "alice")
        chain.refresh(this, userId = "alice", path = projectB)

        // Flat-name registries silently drop the first project's entry here; keyed by (name, project) both live.
        assertEquals(2, chain.registry.all().count { it.name == "pdf-report" })
        val rows = chain.catalog.listByName("pdf-report", SkillCatalogEntry.DEFAULT_USER_ID)
        assertEquals(2, rows.size, "one unique index over (user, name, project_hash) means two rows, not one overwritten")
        assertEquals(2, rows.map { it.projectHash }.distinct().size)

        val loadedA = chain.load(this, "pdf-report", userId = "alice", allowed = listOf("pdf-report"))
        assertFalse(loadedA.isError, "project A must resolve its own skill: ${loadedA.text()}")
        assertTrue("from project A." in loadedA.text(), "the body is the project A file: ${loadedA.text()}")
        assertTrue(dirA.toString() in loadedA.text(), "the advertised base directory must not leak from B")

        val loadedB = chain.load(this, "pdf-report", userId = "alice", allowed = listOf("pdf-report"), path = projectB)
        assertFalse(loadedB.isError, "project B must resolve its own skill: ${loadedB.text()}")
        assertTrue("from project B." in loadedB.text(), "the body is the project B file: ${loadedB.text()}")
        assertTrue(dirB.toString() in loadedB.text(), "the advertised base directory must not leak from A")
    }

    @Test
    fun `a fresh server instance serves a project it never refreshed, from the table alone`(@TempDir tempDir: Path) = runBlocking {
        val chain = Chain(tempDir)
        chain.writeProjectSkill("pdf-report")
        chain.refresh(this, userId = "alice", allowed = listOf("pdf-report"))

        // A second "deployment": its own registry that has never seen the project, but the same table.
        // No refresh_skills is ever called against it — only the startup pass runs.
        val freshRegistry = DefaultSkillRegistry(discovery, chain.config)
        val freshSync = SkillCatalogSyncService(chain.catalog, chain.config)
        val freshIndexer = SkillIndexer(MemoryStore(), chain.catalog, freshSync, chain.config)
        val freshRefresher = SkillRefreshService(freshRegistry, chain.catalog, freshSync, freshIndexer, config = chain.config)

        freshRefresher.reconcileAllOwners()

        assertNotNull(
            freshRegistry.get("pdf-report", chain.project),
            "the project roots the table knows must be hydrated at startup, not only on the next refresh_skills"
        )
        val loaded = chain.load(
            this, "pdf-report", userId = "alice",
            allowed = listOf("pdf-report"), targetRegistry = freshRegistry
        )
        assertFalse(loaded.isError, "hydrated from the DB and loadable in one breath: ${loaded.text()}")
        assertTrue("Step 1: do the thing." in loaded.text())
    }

    /** A document plus the slice it was written into, since that pair is what `search` addresses by. */
    private data class Indexed(val slice: String, val entry: SkillEntry)

    /** Owner-scoped rows in memory, keyed the way the table is: `(user_id, name, project_hash)`. */
    private class MemoryCatalog : AsyncSkillCatalogStore {
        private val rows = LinkedHashMap<Triple<String, String, String>, SkillCatalogEntry>()
        private var sequence = 0

        private fun key(entry: SkillCatalogEntry) = Triple(entry.userId, entry.name, entry.projectHash)

        override suspend fun upsert(entry: SkillCatalogEntry): SkillCatalogEntry {
            val persisted = if (entry.id.isBlank()) entry.copy(id = "row-${++sequence}") else entry
            rows[key(persisted)] = persisted
            return persisted
        }

        override suspend fun listByName(name: String, userId: String): List<SkillCatalogEntry> =
            rows.values.filter { it.userId == userId && it.name == name }

        override suspend fun listByUser(userId: String): List<SkillCatalogEntry> =
            rows.values.filter { it.userId == userId }

        override suspend fun listAll(): List<SkillCatalogEntry> = rows.values.toList()

        override suspend fun listDistinctUserIds(): List<String> = rows.values.map { it.userId }.distinct()

        override suspend fun setEnabled(id: String, enabled: Boolean): Boolean {
            val key = rows.entries.firstOrNull { it.value.id == id }?.key ?: return false
            rows[key] = rows.getValue(key).copy(enabled = enabled)
            return true
        }

        override suspend fun updateChecksum(id: String, checksum: String, version: String): Boolean {
            val key = rows.entries.firstOrNull { it.value.id == id }?.key ?: return false
            rows[key] = rows.getValue(key).copy(checksum = checksum, version = version)
            return true
        }

        override suspend fun delete(id: String): Boolean {
            val key = rows.entries.firstOrNull { it.value.id == id }?.key ?: return false
            return rows.remove(key) != null
        }
    }

    /**
     * A retrieval backend that keeps one document per `(slice, key)`, the way EasyRAG's `biz_id`
     * isolation does, so an index write into the wrong slice is as invisible here as it is in production.
     */
    private class MemoryStore : SkillStore {
        private val documents = mutableListOf<Indexed>()

        /** How many index calls the chain made, so a steady-state pass can be proven to cost nothing. */
        var writes: Int = 0
            private set

        /** Whether any write asked the backend to finish before answering — what `skill_search` latency depends on. */
        var awaited: Boolean = false
            private set

        override suspend fun index(
            entries: List<SkillEntry>,
            scope: SkillScope,
            owner: SkillOwnerContext,
            awaitIndexing: Boolean
        ): Int {
            writes++
            awaited = awaited || awaitIndexing
            val target = slice(scope, owner)
            entries.forEach { entry ->
                documents.removeAll { it.slice == target && it.entry.key == entry.key }
                documents += Indexed(target, entry.copy(scope = scope))
            }
            return entries.size
        }

        override suspend fun search(
            query: String,
            scopes: List<SkillScope>,
            owner: SkillOwnerContext,
            topK: Int
        ): List<SkillEntry> {
            val slices = scopes.map { slice(it, owner) }.toSet()
            return documents
                .filter { it.slice in slices }
                .sortedByDescending { it.entry.name.contains(query, ignoreCase = true) }
                .map { it.entry }
                .take(topK)
        }

        override suspend fun delete(name: String, scope: SkillScope, owner: SkillOwnerContext): Boolean {
            val key = SkillEntry.keyFor(name)
            val target = slice(scope, owner)
            return documents.removeAll { it.slice == target && it.entry.key == key }
        }

        private fun slice(scope: SkillScope, owner: SkillOwnerContext): String =
            "${owner.userId ?: SkillCatalogEntry.DEFAULT_USER_ID}-${scope}-${owner.projectPath ?: ""}"
    }
}
