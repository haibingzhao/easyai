package com.easy.easyai.apps.web.seed

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seeds default model providers into the database on application startup.
 * This runs only once when the database is empty.
 */
@Component
class DefaultModelProviderSeeder(
    private val configStore: ModelProviderConfigStore
) : SmartLifecycle {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    private fun doSeed() {
        scope.launch {
            seed()
        }
    }

    private suspend fun seed() {
        val existingConfigs = configStore.getAllConfigs()
        if (existingConfigs.isEmpty()) {
            logger.info("Database is empty, seeding {} default model providers", DEFAULT_PROVIDERS.size)
            DEFAULT_PROVIDERS.forEach { config ->
                configStore.saveConfig(config)
            }
            logger.info("Default model providers seeded successfully")
        } else {
            logger.info("Database already has {} model provider configs, skipping seed", existingConfigs.size)
        }
    }

    companion object {
        private val DEFAULT_PROVIDERS = listOf(
            ModelProviderConfig(
                id = "openai",
                name = "OpenAI",
                protocol = Protocol.OPENAI,
                isCustom = false,
                baseUrl = "https://api.openai.com/v1",
                apiKey = null,
                modelId = "gpt-4o",
                modelName = "GPT-4o",
                isCustomModel = false,
                enabled = true,
                options = null
            ),
            ModelProviderConfig(
                id = "anthropic",
                name = "Anthropic",
                protocol = Protocol.ANTHROPIC,
                isCustom = false,
                baseUrl = "https://api.anthropic.com",
                apiKey = null,
                modelId = "claude-sonnet-4-20250514",
                modelName = "Claude Sonnet 4",
                isCustomModel = false,
                enabled = true,
                options = null
            ),
        )
    }

    override fun start() {
        if (running.compareAndSet(false, true)) {
            doSeed()
        }
    }

    override fun stop() {
        running.set(false)
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE // Start after database migration
}
