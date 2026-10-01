package com.easy.easyai.autoconfigure.media

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.media.MediaProviderSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [DefaultMediaProviderResolver] — the entry projection, the `model` argument match order,
 * the source layer reporting and the per-user cache invalidation contract of [refresh].
 */
class DefaultMediaProviderResolverTest {

    private class FakeStore : ModelProviderConfigStore {
        val rows = mutableListOf<ModelProviderConfig>()
        val reads = mutableListOf<Pair<String, ModelType>>()

        override suspend fun getConfig(id: String, userId: String): ModelProviderConfig? =
            rows.firstOrNull { it.id == id }

        override suspend fun saveConfig(config: ModelProviderConfig, userId: String) {
            rows.add(config.copy(userId = userId))
        }

        override suspend fun deleteConfig(id: String, userId: String): Boolean = rows.removeAll { it.id == id }

        override suspend fun getModelConfigs(modelType: ModelType, userId: String): List<ModelProviderConfig> {
            reads.add(userId to modelType)
            return rows.filter { it.modelType == modelType && (it.userId == userId || it.userId == "system") }
        }
    }

    private fun row(
        name: String,
        modelType: ModelType,
        owner: String,
        modelId: String = name.lowercase(),
        apiKey: String = "sk-test",
        baseUrl: String = "https://example.invalid/v1",
        enabled: Boolean = true,
        isCustom: Boolean = true,
        protocol: Protocol = Protocol.OPENAI,
        isDefault: Boolean = false
    ) = ModelProviderConfig(
        id = UUID.randomUUID().toString(),
        name = name,
        protocol = protocol,
        isCustom = isCustom,
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelId = modelId,
        enabled = enabled,
        modelType = modelType,
        isDefault = isDefault,
        userId = owner
    )

    @Nested
    inner class `entry projection` {

        @Test
        fun `disabled and structurally invalid rows are dropped`() = runBlocking {
            val store = FakeStore()
            val good = row("wan", ModelType.IMAGE, "alice")
            val disabled = row("off", ModelType.IMAGE, "alice", enabled = false)
            val noKey = row("keyless", ModelType.IMAGE, "alice", apiKey = " ")
            val noUrl = row("pathless", ModelType.IMAGE, "alice", baseUrl = " ")
            val builtin = row("catalog", ModelType.IMAGE, "alice", isCustom = false)
            store.rows += listOf(good, disabled, noKey, noUrl, builtin)

            val resolver = DefaultMediaProviderResolver(store)
            val entries = resolver.resolveEntries("alice", MediaProviderSettings.SERVICE_KIND_IMAGE)

            assertEquals(listOf("wan"), entries.map { it.displayName })
            assertEquals("wan", entries.single().defaultModel)
            assertEquals(MediaProviderSettings.PROVIDER_OPENAI, entries.single().providerType)
        }

        @Test
        fun `unknown service kind yields no entries`() = runBlocking {
            val store = FakeStore()
            store.rows += row("wan", ModelType.IMAGE, "alice")
            val resolver = DefaultMediaProviderResolver(store)
            assertTrue(resolver.resolveEntries("alice", "hologram").isEmpty())
            assertEquals(MediaProviderSource.NONE, resolver.sourceOf("alice", "hologram"))
        }

        @Test
        fun `empty store answers empty everywhere`() = runBlocking {
            val resolver = DefaultMediaProviderResolver(FakeStore())
            assertTrue(resolver.resolveEntries("alice", MediaProviderSettings.SERVICE_KIND_VIDEO).isEmpty())
            assertNull(resolver.resolveEntry("alice", MediaProviderSettings.SERVICE_KIND_VIDEO, null))
        }
    }

    @Nested
    inner class `model match order` {

        @Test
        fun `model id wins over display name wins over default wins over first`() = runBlocking {
            val store = FakeStore()
            val flux = row("Flux Dev", ModelType.IMAGE, "alice", modelId = "flux-dev")
            val wan = row("Wan Default", ModelType.IMAGE, "alice", modelId = "wan-2.5", isDefault = true)
            val other = row("Other", ModelType.IMAGE, "alice", modelId = "other-model")
            store.rows += listOf(flux, wan, other)
            val resolver = DefaultMediaProviderResolver(store)

            assertEquals("flux-dev", resolver.resolveEntry("alice", "image", "FLUX-DEV")?.defaultModel)
            assertEquals("wan-2.5", resolver.resolveEntry("alice", "image", "Wan Default")?.defaultModel)
            assertEquals("flux-dev", resolver.resolveEntry("alice", "image", "Flux Dev")?.defaultModel)
            assertEquals("wan-2.5", resolver.resolveEntry("alice", "image", null)?.defaultModel)
            assertEquals("wan-2.5", resolver.resolveEntry("alice", "image", "nonexistent")?.defaultModel)
        }

        @Test
        fun `a sole entry serves without a default flag`() = runBlocking {
            val store = FakeStore()
            store.rows += row("kling", ModelType.VIDEO, "alice", protocol = Protocol.KLING)
            val resolver = DefaultMediaProviderResolver(store)
            assertEquals("kling", resolver.resolveEntry("alice", "video", null)?.defaultModel)
            assertEquals(MediaProviderSettings.PROVIDER_KLING, resolver.resolveEntry("alice", "video", null)?.providerType)
        }
    }

    @Nested
    inner class `source and cache` {

        @Test
        fun `source reports the layer the leading entry came from`() = runBlocking {
            val store = FakeStore()
            store.rows += row("sys-image", ModelType.IMAGE, "system")
            val resolver = DefaultMediaProviderResolver(store)
            assertEquals(MediaProviderSource.SYSTEM, resolver.sourceOf("bob", "image"))

            store.rows += row("bob-image", ModelType.IMAGE, "bob", isDefault = true)
            resolver.refresh("bob")
            assertEquals(MediaProviderSource.USER, resolver.sourceOf("bob", "image"))
        }

        @Test
        fun `cached reads skip the store until the owning user refreshes`() = runBlocking {
            val store = FakeStore()
            store.rows += row("wan", ModelType.IMAGE, "alice")
            val resolver = DefaultMediaProviderResolver(store)

            resolver.resolveEntries("alice", "image")
            resolver.resolveEntries("alice", "image")
            assertEquals(1, store.reads.size)

            resolver.refresh("alice")
            resolver.resolveEntries("alice", "image")
            assertEquals(2, store.reads.size)
        }

        @Test
        fun `refresh of system drops every cached user`() = runBlocking {
            val store = FakeStore()
            store.rows += row("sys-image", ModelType.IMAGE, "system")
            val resolver = DefaultMediaProviderResolver(store)

            resolver.resolveEntries("alice", "image")
            resolver.resolveEntries("bob", "image")
            assertEquals(2, store.reads.size)

            resolver.refresh(DefaultMediaProviderResolver.SYSTEM_USER_ID)
            resolver.resolveEntries("alice", "image")
            resolver.resolveEntries("bob", "image")
            assertEquals(4, store.reads.size)
        }
    }
}
