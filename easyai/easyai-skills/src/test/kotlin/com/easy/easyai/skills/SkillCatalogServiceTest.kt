package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillSyncState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillCatalogServiceTest {
    @TempDir lateinit var temp: Path

    @Nested
    inner class Enablement {
        @Test
        fun `enabling publishes disabled edits before submitting identical content`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", "old body")
            chain.refresher.refreshFor("alice", chain.project)
            val owner = SkillOwnerContext("alice", chain.project)
            chain.management.setEnabled("draft", owner, false)
            val stamp = Files.getLastModifiedTime(file)
            file.writeText("---\nname: draft\ndescription: new description\nversion: 2.0.0\n---\nnew body")
            Files.setLastModifiedTime(file, stamp)
            chain.remote.onSubmit = {
                assertEquals("new body", chain.registry.get("draft", chain.project)?.content)
                assertEquals("new description", chain.registry.get("draft", chain.project)?.description)
            }
            val result = chain.management.setEnabled("draft", owner, true)
            assertIs<SkillToggleResult.Applied>(result)
            assertTrue(result.enabled)
            assertTrue(result.indexSynced)
            val row = chain.catalog.listAll().single()
            assertEquals("2.0.0", row.version)
            assertEquals(SkillChecksums.sha256Hex(Files.readAllBytes(file)), row.checksum)
            assertEquals(row.checksum, row.indexedChecksum)
            assertEquals("new body", chain.remote.submitted.last().content)
        }

        @Test
        fun `missing or invalid source cannot be enabled`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft")
            chain.refresher.refreshFor("alice", chain.project)
            val owner = SkillOwnerContext("alice", chain.project)
            chain.management.setEnabled("draft", owner, false)
            file.writeText("---\ndescription: no name\n---\nbroken")
            assertIs<SkillToggleResult.Rejected>(chain.management.setEnabled("draft", owner, true))
            Files.delete(file)
            assertIs<SkillToggleResult.Rejected>(chain.management.setEnabled("draft", owner, true))
            assertFalse(chain.catalog.listAll().single().enabled)
        }

        @Test
        fun `disable without rag is immediate but does not claim remote absence`() = runTest {
            val chain = SkillSyncFixture(temp, withRemote = false)
            chain.write("draft")
            chain.refresher.refreshFor("alice", chain.project)
            val result = chain.management.setEnabled("draft", SkillOwnerContext("alice", chain.project), false)
            assertIs<SkillToggleResult.Applied>(result)
            assertFalse(result.indexSynced)
            val row = chain.catalog.listAll().single()
            assertFalse(row.enabled)
            assertEquals(SkillSyncState.PENDING_DELETE, row.syncState)
        }

        @Test
        fun `async enable does not report synced on acceptance`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft")
            chain.refresher.refreshFor("alice", chain.project)
            val owner = SkillOwnerContext("alice", chain.project)
            chain.management.setEnabled("draft", owner, false)
            chain.remote.asynchronous = true
            val result = chain.management.setEnabled("draft", owner, true)
            assertIs<SkillToggleResult.Applied>(result)
            assertFalse(result.indexSynced)
            assertEquals(SkillSyncState.SUBMITTED, chain.catalog.listAll().single().syncState)
        }

        @Test
        fun `other users and anonymous requests cannot toggle shared or private rows`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("shared", global = true)
            chain.refresher.reconcileAllOwners()
            assertIs<SkillToggleResult.Rejected>(chain.management.setEnabled("shared", SkillOwnerContext("alice"), false))
            assertIs<SkillToggleResult.Rejected>(chain.management.setEnabled("shared", SkillOwnerContext(null), false))
            chain.write("private")
            chain.refresher.refreshFor("alice", chain.project)
            assertIs<SkillToggleResult.Rejected>(chain.management.setEnabled("private", SkillOwnerContext("bob", chain.project), false))
            assertTrue(chain.catalog.listAll().all { it.enabled })
        }
    }

    @Nested
    inner class Views {
        @Test
        fun `management view reports project and disk presence without another owner rows`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft")
            chain.refresher.refreshFor("alice", chain.project)
            val owner = SkillOwnerContext("alice", chain.project)
            val view = chain.management.find("draft", owner)!!
            assertEquals(SkillScope.PROJECT, view.scope)
            assertTrue(view.installedOnDisk)
            assertNull(chain.management.find("draft", SkillOwnerContext("bob", chain.project)))
            Files.delete(file)
            assertFalse(chain.management.list(owner).single().installedOnDisk)
        }
    }
}
