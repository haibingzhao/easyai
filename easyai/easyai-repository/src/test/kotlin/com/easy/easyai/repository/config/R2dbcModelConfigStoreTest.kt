package com.easy.easyai.repository.config

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.delay
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
import kotlin.test.assertTrue

/**
 * Integration tests for [R2dbcModelConfigStore] around the modelType partition introduced by V11:
 * the CHAT-default filter is the safety gate that keeps generation rows out of chat selectors, and
 * isDefault is mutually exclusive within one owner + modelType.
 * Uses an in-memory H2 R2DBC database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcModelConfigStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///modelcfg_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun createStore() = R2dbcModelConfigStore(db)

    private fun config(
        name: String,
        modelType: ModelType,
        protocol: Protocol = Protocol.OPENAI,
        apiKey: String? = "sk-$name",
        mediaOptions: String? = null,
        isDefault: Boolean = false,
        modelId: String = "$name-model"
    ) = ModelProviderConfig(
        id = UUID.randomUUID().toString(),
        name = name,
        protocol = protocol,
        isCustom = true,
        baseUrl = "https://example.invalid/v1",
        apiKey = apiKey,
        modelId = modelId,
        isCustomModel = true,
        timeoutSeconds = 120L,
        modelType = modelType,
        mediaOptions = mediaOptions,
        isDefault = isDefault
    )

    @Nested
    inner class `modelType partitioning` {

        @Test
        fun `getAllConfigs hides generation rows`() = runTest {
            val store = createStore()
            val user = "user-${UUID.randomUUID()}"
            store.saveConfig(config("chat-a", ModelType.CHAT), user)
            store.saveConfig(config("image-a", ModelType.IMAGE), user)
            store.saveConfig(config("video-a", ModelType.VIDEO), user)

            val chatOnly = store.getAllConfigs(user)
            assertEquals(setOf("chat-a"), chatOnly.map { it.name }.toSet())
            assertTrue(chatOnly.all { it.modelType == ModelType.CHAT })

            assertEquals(setOf("image-a"), store.getModelConfigs(ModelType.IMAGE, user).map { it.name }.toSet())
            assertEquals(setOf("video-a"), store.getModelConfigs(ModelType.VIDEO, user).map { it.name }.toSet())
        }

        @Test
        fun `generation row round-trips modelType mediaOptions timeout and isDefault`() = runTest {
            val store = createStore()
            val user = "user-${UUID.randomUUID()}"
            val saved = config(
                "wan-image", ModelType.IMAGE,
                protocol = Protocol.DASHSCOPE,
                mediaOptions = """{"size":"1024x1024"}""",
                isDefault = true
            )
            store.saveConfig(saved, user)

            val loaded = store.getConfig(saved.id, user)!!
            assertEquals(ModelType.IMAGE, loaded.modelType)
            assertEquals(Protocol.DASHSCOPE, loaded.protocol)
            assertEquals("""{"size":"1024x1024"}""", loaded.mediaOptions)
            assertEquals(120L, loaded.timeoutSeconds)
            assertTrue(loaded.isDefault)
            assertEquals(user, loaded.userId)
        }

        @Test
        fun `system rows are visible to other users of the same type`() = runTest {
            val store = createStore()
            val system = config("sys-image", ModelType.IMAGE, isDefault = true)
            store.saveConfig(system, "system")
            val other = "user-${UUID.randomUUID()}"

            assertEquals(setOf("sys-image"), store.getModelConfigs(ModelType.IMAGE, other).map { it.name }.toSet())
        }
    }

    @Nested
    inner class `isDefault mutual exclusion` {

        @Test
        fun `saving a new default clears the sibling default of the same type only`() = runTest {
            val store = createStore()
            val user = "user-${UUID.randomUUID()}"
            val firstImage = config("image-first", ModelType.IMAGE, isDefault = true)
            store.saveConfig(firstImage, user)
            val chatDefault = config("chat-default", ModelType.CHAT, isDefault = true)
            store.saveConfig(chatDefault, user)

            val secondImage = config("image-second", ModelType.IMAGE, isDefault = true)
            store.saveConfig(secondImage, user)

            assertFalse(store.getConfig(firstImage.id, user)!!.isDefault)
            assertTrue(store.getConfig(secondImage.id, user)!!.isDefault)
            assertTrue(store.getConfig(chatDefault.id, user)!!.isDefault, "a new IMAGE default must not clear the CHAT default")
        }

        @Test
        fun `other users keep their own default of the same type`() = runTest {
            val store = createStore()
            val alice = "user-a-${UUID.randomUUID()}"
            val bob = "user-b-${UUID.randomUUID()}"
            val aliceDefault = config("alice-image", ModelType.IMAGE, isDefault = true)
            store.saveConfig(aliceDefault, alice)

            store.saveConfig(config("bob-image", ModelType.IMAGE, isDefault = true), bob)

            assertTrue(store.getConfig(aliceDefault.id, alice)!!.isDefault)
        }
    }

    @Nested
    inner class `read ordering` {

        @Test
        fun `the default row leads and the rest keep creation order`() = runTest {
            val store = createStore()
            val user = "user-${UUID.randomUUID()}"
            store.saveConfig(config("image-old", ModelType.IMAGE), user)
            delay(2)
            store.saveConfig(config("image-new", ModelType.IMAGE), user)
            delay(2)
            store.saveConfig(config("image-default", ModelType.IMAGE, isDefault = true), user)

            assertEquals(
                listOf("image-default", "image-old", "image-new"),
                store.getModelConfigs(ModelType.IMAGE, user).map { it.name }
            )

            store.deleteConfig(store.getModelConfigs(ModelType.IMAGE, user).first { it.isDefault }.id, user)

            assertEquals(
                listOf("image-old", "image-new"),
                store.getModelConfigs(ModelType.IMAGE, user).map { it.name },
                "without an explicit default the oldest row wins"
            )
        }
    }
}
