package com.easy.easyai.autoconfigure.storage

import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.core.storage.StorageSettings
import com.easy.easyai.core.storage.StorageSettingsStore
import com.easy.easyai.core.storage.StorageSettingsService
import com.easy.easyai.core.storage.StorageSource
import com.easy.easyai.storage.local.LocalDirObjectStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Condition and chain tests for [ObjectStorageAutoConfiguration] and [DefaultObjectStorageResolver].
 *
 * The database is the only configuration source, so the resolver has to be honest in both
 * directions: nothing resolves while no row exists, and a saved row wins over (or shadows) the
 * shared `system` row without a restart.
 */
class ObjectStorageAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(ObjectStorageAutoConfiguration::class.java)

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

    private fun configuration(): ObjectStorageAutoConfiguration = ObjectStorageAutoConfiguration()

    private fun localRow(dir: String) =
        StorageSettings(enabled = true, type = "local", localDir = dir)

    private fun resolverWith(store: StorageSettingsStore?): ObjectStorageResolver =
        configuration().objectStorageResolver(store)

    @Nested
    inner class `the resolver bean` {

        @Test
        fun `nothing resolves while the database holds no rows`() {
            contextRunner.run { context ->
                val resolver = context.getBean(ObjectStorageResolver::class.java)

                // The only ObjectStorage bean is the local media-directory fallback, which the
                // resolver deliberately does not expose: per-user resolution stays row-driven.
                assertEquals(listOf("localMediaObjectStorage"), context.getBeanNamesForType(ObjectStorage::class.java).toList())
                assertIs<LocalDirObjectStorage>(context.getBean(ObjectStorage::class.java))
                assertEquals(null, runBlocking { resolver.resolve("alice") })
                assertEquals(StorageSource.NONE, runBlocking { resolver.sourceOf("alice") })
            }
        }

        @Test
        fun `the settings service is wired alongside the resolver`() {
            contextRunner.run { context ->
                assertEquals(1, context.getBeanNamesForType(StorageSettingsService::class.java).size)
            }
        }
    }

    @Nested
    inner class `the per-user chain` {

        @Test
        fun `a stored row resolves to its own backend`() = runBlocking {
            val store = InMemorySettingsStore()
            store.rows["alice"] = localRow(root.resolve("alice").toString())
            val resolver = resolverWith(store)

            val storage = resolver.resolve("alice") ?: error("alice has a stored row")
            storage.put("probe.txt", byteArrayOf(7), "text/plain")

            assertTrue(Files.exists(root.resolve("alice/probe.txt")), "the stored row's directory must be the one written to")
            assertEquals(StorageSource.USER, resolver.sourceOf("alice"))
            assertEquals(StorageSource.NONE, resolver.sourceOf("bob"), "a user without any row gets nothing")
        }

        @Test
        fun `the system row is the shared fallback for users without their own`() = runBlocking {
            val store = InMemorySettingsStore()
            store.rows["system"] = localRow(root.resolve("system").toString())
            val resolver = resolverWith(store)

            assertIs<LocalDirObjectStorage>(resolver.resolve("bob"))
            assertEquals(StorageSource.SYSTEM, resolver.sourceOf("bob"))
        }

        @Test
        fun `an explicit disabled row shadows the shared system row`() = runBlocking {
            val store = InMemorySettingsStore()
            store.rows["system"] = localRow(root.resolve("system").toString())
            store.rows["alice"] = StorageSettings(enabled = false, type = "local")
            val resolver = resolverWith(store)

            assertEquals(null, resolver.resolve("alice"))
            assertEquals(StorageSource.NONE, resolver.sourceOf("alice"))
            assertEquals(StorageSource.SYSTEM, resolver.sourceOf("bob"))
        }

        @Test
        fun `a saved configuration takes effect without a restart`() = runBlocking {
            val store = InMemorySettingsStore()
            val resolver = resolverWith(store)
            val before = resolver.resolve("alice")

            store.rows["alice"] = localRow(root.resolve("alice-v2").toString())
            val stale = resolver.resolve("alice")
            resolver.refresh("alice")
            val after = resolver.resolve("alice") ?: error("the row is enabled")

            assertSame(before, stale, "the cache must hold until refresh")
            after.put("probe.txt", byteArrayOf(7), "text/plain")
            assertTrue(Files.exists(root.resolve("alice-v2/probe.txt")))
            assertEquals(StorageSource.USER, resolver.sourceOf("alice"))
        }

        @Test
        fun `a corrupt stored row fails loudly instead of falling through`() = runBlocking {
            val store = InMemorySettingsStore()
            store.rows["alice"] = StorageSettings(enabled = true, type = "aliyun")
            val resolver = resolverWith(store)

            val error = assertFailsWith<ObjectStorageException> { resolver.resolve("alice") }
            assertTrue("invalid" in error.message!!, "got: ${error.message}")
        }
    }
}
