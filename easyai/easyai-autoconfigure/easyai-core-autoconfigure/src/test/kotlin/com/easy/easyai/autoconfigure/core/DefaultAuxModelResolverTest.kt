package com.easy.easyai.autoconfigure.core

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.model.aux.AuxModelSettings
import com.easy.easyai.core.model.aux.AuxModelSettingsStore
import com.easy.easyai.core.model.aux.AuxModelTask
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import kotlin.test.assertNull
import kotlin.test.assertSame

class DefaultAuxModelResolverTest {

    private val store = mockk<AuxModelSettingsStore>()
    private val configStore = mockk<ModelProviderConfigStore>()
    private val factory = mockk<ChatModelFactory>()
    private val chatModel = mockk<ChatModel>()
    private val config = mockk<ModelProviderConfig>()

    private fun resolver(vararg factories: ChatModelFactory) =
        DefaultAuxModelResolver(store, configStore, factories.toList())

    private fun stubResolvedConfig(protocol: Protocol = Protocol.OPENAI) {
        every { config.protocol } returns protocol
        coEvery { configStore.getConfig("cfg-1", "user-1") } returns config
        every { factory.supports(protocol) } returns true
        every { factory.create(config, any()) } returns chatModel
    }

    @Nested
    inner class `unconfigured falls back to null` {

        @Test
        fun `no stored row returns null`() = runTest {
            coEvery { store.get("user-1", AuxModelTask.COMPACTION) } returns null
            assertNull(resolver(factory).resolve("user-1", AuxModelTask.COMPACTION))
        }

        @Test
        fun `blank model config id returns null`() = runTest {
            coEvery { store.get("user-1", AuxModelTask.COMPACTION) } returns
                AuxModelSettings(AuxModelTask.COMPACTION.key, "")
            assertNull(resolver(factory).resolve("user-1", AuxModelTask.COMPACTION))
        }

        @Test
        fun `null user returns null without touching the store`() = runTest {
            assertNull(resolver(factory).resolve(null, AuxModelTask.COMPACTION))
            coVerify(exactly = 0) { store.get(any<String>(), any()) }
        }

        @Test
        fun `missing store returns null`() = runTest {
            val r = DefaultAuxModelResolver(null, configStore, listOf(factory))
            assertNull(r.resolve("user-1", AuxModelTask.COMPACTION))
        }
    }

    @Nested
    inner class `configured resolves the referenced model` {

        @Test
        fun `returns the built chat model and its config`() = runTest {
            coEvery { store.get("user-1", AuxModelTask.COMPACTION) } returns
                AuxModelSettings(AuxModelTask.COMPACTION.key, "cfg-1")
            stubResolvedConfig()

            val resolved = resolver(factory).resolve("user-1", AuxModelTask.COMPACTION)

            assertSame(chatModel, resolved?.chatModel)
            assertSame(config, resolved?.modelConfig)
        }

        @Test
        fun `deleted config returns null`() = runTest {
            coEvery { store.get("user-1", AuxModelTask.COMPACTION) } returns
                AuxModelSettings(AuxModelTask.COMPACTION.key, "cfg-1")
            coEvery { configStore.getConfig("cfg-1", "user-1") } returns null

            assertNull(resolver(factory).resolve("user-1", AuxModelTask.COMPACTION))
        }

        @Test
        fun `no factory for the protocol returns null`() = runTest {
            coEvery { store.get("user-1", AuxModelTask.COMPACTION) } returns
                AuxModelSettings(AuxModelTask.COMPACTION.key, "cfg-1")
            every { config.protocol } returns Protocol.ANTHROPIC
            coEvery { configStore.getConfig("cfg-1", "user-1") } returns config
            every { factory.supports(Protocol.ANTHROPIC) } returns false

            assertNull(resolver(factory).resolve("user-1", AuxModelTask.COMPACTION))
        }
    }

    @Nested
    inner class `caching` {

        @Test
        fun `second resolve is served from cache without re-reading config`() = runTest {
            coEvery { store.get("user-1", AuxModelTask.COMPACTION) } returns
                AuxModelSettings(AuxModelTask.COMPACTION.key, "cfg-1")
            stubResolvedConfig()
            val r = resolver(factory)

            r.resolve("user-1", AuxModelTask.COMPACTION)
            r.resolve("user-1", AuxModelTask.COMPACTION)

            coVerify(exactly = 1) { configStore.getConfig("cfg-1", "user-1") }
        }

        @Test
        fun `refresh drops the cache entry so the next resolve re-reads`() = runTest {
            coEvery { store.get("user-1", AuxModelTask.COMPACTION) } returns
                AuxModelSettings(AuxModelTask.COMPACTION.key, "cfg-1")
            stubResolvedConfig()
            val r = resolver(factory)

            r.resolve("user-1", AuxModelTask.COMPACTION)
            r.refresh("user-1", AuxModelTask.COMPACTION)
            r.resolve("user-1", AuxModelTask.COMPACTION)

            coVerify(exactly = 2) { configStore.getConfig("cfg-1", "user-1") }
        }

        @Test
        fun `tasks are cached independently`() = runTest {
            coEvery { store.get("user-1", AuxModelTask.COMPACTION) } returns
                AuxModelSettings(AuxModelTask.COMPACTION.key, "cfg-1")
            coEvery { store.get("user-1", AuxModelTask.SESSION_TITLE) } returns null
            stubResolvedConfig()
            val r = resolver(factory)

            r.resolve("user-1", AuxModelTask.COMPACTION)
            assertNull(r.resolve("user-1", AuxModelTask.SESSION_TITLE))

            coVerify(exactly = 1) { store.get("user-1", AuxModelTask.COMPACTION) }
        }
    }

    @Nested
    inner class `resolveConfig returns the raw row` {

        private fun stubSelectionConfig() {
            coEvery { store.get("user-1", AuxModelTask.SKILL_SELECTION) } returns
                AuxModelSettings(AuxModelTask.SKILL_SELECTION.key, "cfg-1")
            coEvery { configStore.getConfig("cfg-1", "user-1") } returns config
        }

        @Test
        fun `never consults chat model factories`() = runTest {
            stubSelectionConfig()
            val r = resolver(factory)

            assertSame(config, r.resolveConfig("user-1", AuxModelTask.SKILL_SELECTION))

            verify(exactly = 0) { factory.supports(any()) }
            verify(exactly = 0) { factory.create(any(), any()) }
        }

        @Test
        fun `second resolve is served from cache and refresh re-reads`() = runTest {
            stubSelectionConfig()
            val r = resolver(factory)

            r.resolveConfig("user-1", AuxModelTask.SKILL_SELECTION)
            r.resolveConfig("user-1", AuxModelTask.SKILL_SELECTION)
            coVerify(exactly = 1) { configStore.getConfig("cfg-1", "user-1") }

            r.refresh("user-1", AuxModelTask.SKILL_SELECTION)
            r.resolveConfig("user-1", AuxModelTask.SKILL_SELECTION)
            coVerify(exactly = 2) { configStore.getConfig("cfg-1", "user-1") }
        }

        @Test
        fun `unconfigured blank or missing returns null`() = runTest {
            val r = resolver(factory)
            coEvery { store.get("user-1", AuxModelTask.SKILL_SELECTION) } returns null
            assertNull(r.resolveConfig("user-1", AuxModelTask.SKILL_SELECTION))

            coEvery { store.get("user-1", AuxModelTask.SKILL_SELECTION) } returns
                AuxModelSettings(AuxModelTask.SKILL_SELECTION.key, "")
            assertNull(r.resolveConfig("user-1", AuxModelTask.SKILL_SELECTION))

            coEvery { store.get("user-1", AuxModelTask.SKILL_SELECTION) } returns
                AuxModelSettings(AuxModelTask.SKILL_SELECTION.key, "cfg-1")
            coEvery { configStore.getConfig("cfg-1", "user-1") } returns null
            assertNull(r.resolveConfig("user-1", AuxModelTask.SKILL_SELECTION))

            assertNull(r.resolveConfig(null, AuxModelTask.SKILL_SELECTION))
        }

        @Test
        fun `config cache is independent from the chat model cache`() = runTest {
            stubSelectionConfig()
            stubResolvedConfig()
            coEvery { store.get("user-1", AuxModelTask.COMPACTION) } returns
                AuxModelSettings(AuxModelTask.COMPACTION.key, "cfg-1")
            val r = resolver(factory)

            r.refresh("user-1", AuxModelTask.SKILL_SELECTION)
            assertSame(config, r.resolveConfig("user-1", AuxModelTask.SKILL_SELECTION))
            assertSame(chatModel, r.resolve("user-1", AuxModelTask.COMPACTION)?.chatModel)
        }
    }

    @Nested
    inner class `group owners resolve in priority order` {

        @Test
        fun `self choice wins over the group bucket`() = runTest {
            coEvery { store.get("alice", AuxModelTask.COMPACTION) } returns
                AuxModelSettings(AuxModelTask.COMPACTION.key, "cfg-self")
            coEvery { configStore.getConfig("cfg-self", "alice") } returns config
            every { config.protocol } returns Protocol.OPENAI
            every { factory.supports(Protocol.OPENAI) } returns true
            every { factory.create(config, any()) } returns chatModel
            val r = resolver(factory)

            assertSame(chatModel, r.resolve(listOf("alice", "grp-1", "system"), AuxModelTask.COMPACTION)?.chatModel)

            coVerify(exactly = 0) { store.get("grp-1", any()) }
            coVerify(exactly = 1) { configStore.getConfig("cfg-self", "alice") }
        }

        @Test
        fun `falls through to the group bucket when self is unconfigured`() = runTest {
            coEvery { store.get("alice", AuxModelTask.COMPACTION) } returns null
            coEvery { store.get("grp-1", AuxModelTask.COMPACTION) } returns
                AuxModelSettings(AuxModelTask.COMPACTION.key, "cfg-grp")
            coEvery { configStore.getConfig("cfg-grp", "grp-1") } returns config
            every { config.protocol } returns Protocol.OPENAI
            every { factory.supports(Protocol.OPENAI) } returns true
            every { factory.create(config, any()) } returns chatModel
            val r = resolver(factory)

            assertSame(config, r.resolve(listOf("alice", "grp-1", "system"), AuxModelTask.COMPACTION)?.modelConfig)

            coVerify(exactly = 1) { configStore.getConfig("cfg-grp", "grp-1") }
            coVerify(exactly = 0) { store.get("system", any()) }
        }

        @Test
        fun `refresh on the group bucket evicts every member resolution`() = runTest {
            coEvery { store.get("alice", AuxModelTask.COMPACTION) } returns null
            coEvery { store.get("bob", AuxModelTask.COMPACTION) } returns null
            coEvery { store.get("grp-1", AuxModelTask.COMPACTION) } returns
                AuxModelSettings(AuxModelTask.COMPACTION.key, "cfg-grp")
            coEvery { configStore.getConfig("cfg-grp", "grp-1") } returns config
            every { config.protocol } returns Protocol.OPENAI
            every { factory.supports(Protocol.OPENAI) } returns true
            every { factory.create(config, any()) } returns chatModel
            val r = resolver(factory)

            r.resolve(listOf("alice", "grp-1"), AuxModelTask.COMPACTION)
            r.resolve(listOf("bob", "grp-1"), AuxModelTask.COMPACTION)
            coVerify(exactly = 2) { configStore.getConfig("cfg-grp", "grp-1") }

            r.refresh("grp-1", AuxModelTask.COMPACTION)
            r.resolve(listOf("alice", "grp-1"), AuxModelTask.COMPACTION)
            r.resolve(listOf("bob", "grp-1"), AuxModelTask.COMPACTION)
            coVerify(exactly = 4) { configStore.getConfig("cfg-grp", "grp-1") }
        }

        @Test
        fun `empty or all-blank owners return null without touching the store`() = runTest {
            val r = resolver(factory)
            assertNull(r.resolve(emptyList<String>(), AuxModelTask.COMPACTION))
            assertNull(r.resolve(listOf("", "  "), AuxModelTask.COMPACTION))
            coVerify(exactly = 0) { store.get(any<String>(), any()) }
        }
    }
}
