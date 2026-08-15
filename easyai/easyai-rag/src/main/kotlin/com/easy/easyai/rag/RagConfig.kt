package com.easy.easyai.rag

import com.easy.easyai.common.util.SharedObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Persistent EasyRAG connection configuration stored at `~/.easyai/rag.json`.
 *
 * Loaded per-request so runtime changes take effect without restart.
 * Mirrors the file-based pattern of `IntegrationConfig`.
 *
 * @param enabled master switch for the RAG integration
 * @param baseUrl EasyRAG server base URL
 * @param username optional credential for JWT login
 * @param password optional credential for JWT login
 * @param workspace optional EasyRAG workspace (null = server default)
 * @param topK default retrieval top-k
 * @param readTimeoutMs timeout for read operations (search / read / list)
 * @param indexTimeoutMs timeout for write + synchronous indexing (embedding cost)
 */
data class RagConfig(
    val enabled: Boolean = true,
    val baseUrl: String = "http://localhost:8020",
    val username: String? = null,
    val password: String? = null,
    val workspace: String? = "easyai",
    val topK: Int = 5,
    val readTimeoutMs: Long = 5000,
    val indexTimeoutMs: Long = 30000
) {
    companion object {
        private val logger = LoggerFactory.getLogger(RagConfig::class.java)

        /** Default config file location: `~/.easyai/rag.json` */
        @JvmStatic
        fun defaultConfigPath(): Path = Path.of(System.getProperty("user.home"), ".easyai", "rag.json")

        /**
         * Load RAG config from the given path.
         * Returns default (enabled) config when the file is missing or unparsable.
         */
        @JvmStatic
        suspend fun load(path: Path = defaultConfigPath()): RagConfig = withContext(Dispatchers.IO) {
            if (!Files.exists(path)) return@withContext RagConfig()
            try {
                val content = Files.readString(path)
                SharedObjectMapper.instance.readValue(content, RagConfig::class.java)
            } catch (e: Exception) {
                logger.warn("Failed to parse rag.json at {}: {}", path, e.message)
                RagConfig()
            }
        }

        /**
         * Save RAG config to the given path, creating parent directories if needed.
         */
        @JvmStatic
        suspend fun save(config: RagConfig, path: Path = defaultConfigPath()) = withContext(Dispatchers.IO) {
            Files.createDirectories(path.parent)
            val content = SharedObjectMapper.instance.writerWithDefaultPrettyPrinter()
                .writeValueAsString(config)
            Files.writeString(path, content)
            logger.info("RAG config saved to {}", path)
        }
    }
}
