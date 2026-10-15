package com.easy.easyai.repository.config

import com.easy.easyai.api.model.CatalogCapabilities
import com.easy.easyai.api.model.CatalogOptions
import com.easy.easyai.api.model.ModelCatalogEntry
import com.easy.easyai.api.model.ModelPricing
import com.easy.easyai.api.model.StructuredOutputSupport
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [R2dbcModelCatalogStore] over an in-memory H2 R2DBC database:
 * the catalog is global (no owner partition), keyed by model id, and upsert distinguishes
 * insert from update while JSON columns round-trip.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcModelCatalogStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///modelcatalog_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun createStore() = R2dbcModelCatalogStore(db)

    private fun entry(
        modelId: String,
        displayName: String = "Test $modelId",
        vision: Boolean = true,
        structuredOutput: StructuredOutputSupport = StructuredOutputSupport.JSON_SCHEMA,
        toolCalling: Boolean = true,
        thinking: Boolean = false,
        maxTokens: Int? = 8192,
        maxContextTokens: Int? = 1_000_000,
        pricing: ModelPricing? = ModelPricing("CNY", 12.0, 36.0, 1.5),
        description: String? = "desc of $modelId",
        rawPayload: String? = """{"model":"$modelId"}"""
    ) = ModelCatalogEntry(
        modelId = modelId,
        vendor = "bailian",
        displayName = displayName,
        description = description,
        capabilities = CatalogCapabilities(vision, structuredOutput, toolCalling, thinking),
        defaultOptions = CatalogOptions(maxTokens, maxContextTokens),
        pricing = pricing,
        source = "bailian-refresh",
        rawPayload = rawPayload
    )

    @Nested
    inner class `lookup and listing` {

        @Test
        fun `lookup on an empty catalog returns null`() = runTest {
            val store = createStore()
            assertNull(store.getByModelId("no-such-model-${UUID.randomUUID()}"))
        }

        @Test
        fun `upsert inserts then round-trips every column`() = runTest {
            val store = createStore()
            val modelId = "round-trip-${UUID.randomUUID()}"
            assertTrue(store.upsert(entry(modelId)), "first upsert must report an insert")

            val loaded = assertNotNull(store.getByModelId(modelId))
            assertEquals("bailian", loaded.vendor)
            assertEquals("Test $modelId", loaded.displayName)
            assertEquals("desc of $modelId", loaded.description)
            assertEquals(CatalogCapabilities(true, StructuredOutputSupport.JSON_SCHEMA, true, false), loaded.capabilities)
            assertEquals(CatalogOptions(8192, 1_000_000), loaded.defaultOptions)
            assertEquals(ModelPricing("CNY", 12.0, 36.0, 1.5), loaded.pricing)
            assertEquals("bailian-refresh", loaded.source)
            assertEquals("""{"model":"$modelId"}""", loaded.rawPayload)
            assertTrue(loaded.createdAt > 0 && loaded.updatedAt > 0)
        }

        @Test
        fun `listAll orders by model id`() = runTest {
            val store = createStore()
            val suffix = UUID.randomUUID().toString().take(8)
            val later = entry("zz-$suffix")
            val earlier = entry("aa-$suffix")
            store.upsert(later)
            store.upsert(earlier)

            val ids = store.listAll().map { it.modelId }
            assertTrue(ids.indexOf("aa-$suffix") < ids.indexOf("zz-$suffix"))
        }
    }

    @Nested
    inner class `upsert update semantics` {

        @Test
        fun `second upsert updates in place and keeps createdAt`() = runTest {
            val store = createStore()
            val modelId = "update-me-${UUID.randomUUID()}"
            store.upsert(entry(modelId))
            val created = assertNotNull(store.getByModelId(modelId))

            val revised = entry(
                modelId,
                displayName = "Renamed",
                vision = false,
                structuredOutput = StructuredOutputSupport.NONE,
                toolCalling = false,
                thinking = true,
                maxTokens = 128_000,
                maxContextTokens = 262_144,
                pricing = null
            )
            assertFalse(store.upsert(revised), "second upsert must report an update")

            val loaded = assertNotNull(store.getByModelId(modelId))
            assertEquals("Renamed", loaded.displayName)
            assertEquals(CatalogCapabilities(false, StructuredOutputSupport.NONE, false, true), loaded.capabilities)
            assertEquals(CatalogOptions(128_000, 262_144), loaded.defaultOptions)
            assertNull(loaded.pricing, "null pricing on the new entry clears the stored one")
            assertEquals(created.createdAt, loaded.createdAt)
            assertTrue(loaded.updatedAt >= created.updatedAt)
        }
    }
}
