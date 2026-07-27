package com.easy.easyai.tools.web

import com.easy.easyai.common.util.SharedObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Persistent integration configuration stored at `~/.easyai/integrations.json`.
 *
 * Stores API keys for third-party service integrations (e.g., web search providers).
 * Used by [WebSearchToolBuilder] to resolve API keys at tool build time,
 * and by the frontend Integrations settings page for configuration.
 */
data class IntegrationConfig(
    val exaApiKey: String? = null,
    val parallelApiKey: String? = null,
    val websearchProvider: String? = null
) {
    companion object {
        private val logger = LoggerFactory.getLogger(IntegrationConfig::class.java)

        /** Default config file location: `~/.easyai/integrations.json` */
        fun defaultConfigPath(): Path {
            return Path.of(System.getProperty("user.home"), ".easyai", "integrations.json")
        }

        /**
         * Load integration config from the given path.
         * Returns null if the file does not exist or cannot be parsed.
         */
        fun load(path: Path = defaultConfigPath()): IntegrationConfig? {
            if (!Files.exists(path)) return null
            return try {
                val content = Files.readString(path)
                SharedObjectMapper.instance.readValue(content, IntegrationConfig::class.java)
            } catch (e: Exception) {
                logger.warn("Failed to parse integrations.json at {}: {}", path, e.message)
                null
            }
        }

        /**
         * Save integration config to the given path.
         * Creates parent directories if they don't exist.
         */
        fun save(config: IntegrationConfig, path: Path = defaultConfigPath()) {
            Files.createDirectories(path.parent)
            val content = SharedObjectMapper.instance.writerWithDefaultPrettyPrinter()
                .writeValueAsString(config)
            Files.writeString(path, content)
            logger.info("Integration config saved to {}", path)
        }

        /**
         * Resolve the effective EXA_API_KEY from all sources.
         * Priority: file config > System.getProperty > System.getenv
         */
        fun resolveExaApiKey(): String? {
            val fromFile = load()?.exaApiKey
            if (!fromFile.isNullOrBlank()) return fromFile
            val fromProp = System.getProperty("EXA_API_KEY")
            if (!fromProp.isNullOrBlank()) return fromProp
            val fromEnv = System.getenv("EXA_API_KEY")
            if (!fromEnv.isNullOrBlank()) return fromEnv
            return null
        }

        /**
         * Resolve the effective PARALLEL_API_KEY from all sources.
         * Priority: file config > System.getProperty > System.getenv
         */
        fun resolveParallelApiKey(): String? {
            val fromFile = load()?.parallelApiKey
            if (!fromFile.isNullOrBlank()) return fromFile
            val fromProp = System.getProperty("PARALLEL_API_KEY")
            if (!fromProp.isNullOrBlank()) return fromProp
            val fromEnv = System.getenv("PARALLEL_API_KEY")
            if (!fromEnv.isNullOrBlank()) return fromEnv
            return null
        }

        /**
         * Resolve the effective web search provider preference.
         * Priority: file config > System.getenv("EASYAI_WEBSEARCH_PROVIDER")
         */
        fun resolveWebsearchProvider(): String? {
            val fromFile = load()?.websearchProvider
            if (!fromFile.isNullOrBlank()) return fromFile
            return System.getenv("EASYAI_WEBSEARCH_PROVIDER")
        }

        /**
         * Check if web search is available (at least one API key is configured).
         */
        fun isWebSearchConfigured(): Boolean {
            return !resolveExaApiKey().isNullOrBlank() || !resolveParallelApiKey().isNullOrBlank()
        }
    }
}
