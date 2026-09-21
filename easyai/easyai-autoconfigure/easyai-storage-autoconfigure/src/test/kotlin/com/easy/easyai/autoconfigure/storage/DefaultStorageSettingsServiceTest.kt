package com.easy.easyai.autoconfigure.storage

import com.easy.easyai.core.storage.StorageSettings
import com.easy.easyai.core.storage.StorageSettingsResult
import com.easy.easyai.core.storage.StorageSettingsStore
import com.easy.easyai.core.storage.StorageSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [DefaultStorageSettingsService] — the write side of the hot-apply path.
 *
 * The two contracts that could silently break deployments: a masked read value resubmitted
 * unchanged must not wipe the stored credential, and a broken draft must be refused before it
 * can shadow the row that is currently working.
 */
class DefaultStorageSettingsServiceTest {

    @TempDir
    private lateinit var root: Path

    private class InMemorySettingsStore : StorageSettingsStore {
        val rows: MutableMap<String, StorageSettings> = mutableMapOf()

        override suspend fun get(userId: String): StorageSettings? = rows[userId]

        override suspend fun save(settings: StorageSettings, userId: String) {
            rows[userId] = settings
        }

        override suspend fun delete(userId: String): Boolean = rows.remove(userId) != null
    }

    private fun serviceWith(store: StorageSettingsStore?): Pair<DefaultStorageSettingsService, DefaultObjectStorageResolver> {
        val resolver = DefaultObjectStorageResolver(store)
        return DefaultStorageSettingsService(store, resolver) to resolver
    }

    private fun localRow(dir: String) = StorageSettings(enabled = true, type = "local", localDir = dir)

    @Test
    fun `a blank secret on save keeps the stored credential`() = runBlocking {
        val store = InMemorySettingsStore()
        store.rows["alice"] = StorageSettings(
            enabled = true, type = "aliyun",
            endpoint = "https://oss-cn-hangzhou.aliyuncs.com", bucket = "easyai-market",
            accessKeyId = "LTA-old", accessKeySecret = "sk-stored-12345678"
        )
        val (service, _) = serviceWith(store)
        val draft = store.rows["alice"]!!.copy(accessKeyId = "LTA-new", accessKeySecret = "")

        val outcome = service.save("alice", draft)

        val saved = (outcome as StorageSettingsResult.Saved).settings
        assertEquals("sk-stored-12345678", saved.accessKeySecret, "the UI can only ever echo a mask, so blank means unchanged")
        assertEquals("LTA-new", store.rows["alice"]!!.accessKeyId)
    }

    @Test
    fun `a broken draft is refused before it can shadow a working row`() = runBlocking {
        val store = InMemorySettingsStore()
        store.rows["alice"] = localRow(root.resolve("working").toString())
        val (service, resolver) = serviceWith(store)

        val outcome = service.save("alice", StorageSettings(enabled = true, type = "aliyun"))

        assertTrue(outcome is StorageSettingsResult.Invalid, "a config that cannot build must not be persisted")
        assertTrue("endpoint" in outcome.reason, "got: ${outcome.reason}")
        assertEquals(StorageSource.USER, resolver.sourceOf("alice"), "the previously working row stays in force")
    }

    @Test
    fun `a saved configuration takes effect without a restart`() = runBlocking {
        val store = InMemorySettingsStore()
        val (service, resolver) = serviceWith(store)
        resolver.resolve("alice")

        val outcome = service.save("alice", localRow(root.resolve("alice-v2").toString()))

        assertTrue(outcome is StorageSettingsResult.Saved)
        assertEquals(StorageSource.USER, resolver.sourceOf("alice"), "save must refresh the resolver cache")
        val storage = resolver.resolve("alice") ?: error("the saved row is enabled")
        storage.put("probe.txt", byteArrayOf(7), "text/plain")
        assertTrue(Files.exists(root.resolve("alice-v2/probe.txt")))
    }

    @Test
    fun `saving the system row reaches users who cached the previous one`() = runBlocking {
        val store = InMemorySettingsStore()
        store.rows["system"] = localRow(root.resolve("shared-v1").toString())
        val (service, resolver) = serviceWith(store)
        val before = resolver.resolve("bob") ?: error("the system row is enabled")
        before.put("probe.txt", byteArrayOf(1), "text/plain")

        val outcome = service.save("system", localRow(root.resolve("shared-v2").toString()))

        assertTrue(outcome is StorageSettingsResult.Saved)
        val after = resolver.resolve("bob") ?: error("the new system row is enabled")
        after.put("probe.txt", byteArrayOf(2), "text/plain")
        assertTrue(
            Files.exists(root.resolve("shared-v2/probe.txt")),
            "bob has no own row; his cached SYSTEM entry must not survive a system save"
        )
    }

    @Test
    fun `without a store the write path reports itself unavailable`() = runBlocking {
        val (service, _) = serviceWith(null)

        assertEquals(StorageSettingsResult.Unavailable, service.save("alice", localRow(root.toString())))
    }

    @Test
    fun `a probe round-trips the local backend and reports structural drafts`() = runBlocking {
        val (service, _) = serviceWith(null)

        assertNull(service.probe("alice", localRow(root.resolve("probe").toString())))

        val failure = service.probe("alice", StorageSettings(enabled = true, type = "aliyun"))
        assertNotNull(failure)
        assertTrue("endpoint" in failure, "got: $failure")
    }
}
