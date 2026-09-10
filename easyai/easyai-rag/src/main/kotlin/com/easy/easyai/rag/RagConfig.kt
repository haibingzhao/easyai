package com.easy.easyai.rag

import com.easy.easyai.common.util.SharedObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent EasyRAG connection configuration stored at `~/.easyai/rag.json`.
 *
 * Loaded per-request so runtime changes take effect without restart; the parsed result is
 * memoized per path and re-read only when the file's last-modified time changes, because a
 * request issues several loads and each uncached load costs a disk read plus a parse.
 * Mirrors the file-based pattern of `IntegrationConfig`.
 *
 * @param enabled master switch for the RAG integration
 * @param baseUrl EasyRAG server base URL
 * @param username optional credential for JWT login
 * @param password optional credential for JWT login
 * @param workspace optional EasyRAG workspace (null = server default)
 * @param topK default retrieval top-k
 * @param readTimeoutMs timeout for read operations (search / read / list)
 * @param indexTimeoutMs timeout for document insert / delete operations
 * @param indexSubmitTimeoutMs timeout for the index submission call (async mode)
 * @param indexPollIntervalMs initial interval between status polls during indexing
 * @param indexPollMaxMs maximum total time to poll for indexing completion
 */
data class RagConfig(
    val enabled: Boolean = true,
    val baseUrl: String = "http://localhost:8020",
    val username: String? = null,
    val password: String? = null,
    val workspace: String? = "easyai",
    val topK: Int = 5,
    val readTimeoutMs: Long = 5000,
    val indexTimeoutMs: Long = 30000,
    val indexSubmitTimeoutMs: Long = 10_000,
    val indexPollIntervalMs: Long = 2_000,
    val indexPollMaxMs: Long = 300_000
) {
    companion object {
        private val logger = LoggerFactory.getLogger(RagConfig::class.java)

        /** Memoized parse results keyed by config path, invalidated on last-modified time change. */
        private val cache = ConcurrentHashMap<Path, CachedConfig>()

        private class CachedConfig(val modifiedMillis: Long, val config: RagConfig)

        /** Default config file location: `~/.easyai/rag.json` */
        @JvmStatic
        fun defaultConfigPath(): Path = Path.of(System.getProperty("user.home"), ".easyai", "rag.json")

        /**
         * Load RAG config from the given path.
         * Returns default (enabled) config when the file is missing or unparsable.
         */
        @JvmStatic
        suspend fun load(path: Path = defaultConfigPath()): RagConfig = withContext(Dispatchers.IO) {
            // Modification time doubles as the cache key; it is 0 for an absent file, so a file
            // that appears later always misses the cache instead of reusing the default config.
            val modified = path.toFile().lastModified()
            val cached = cache[path]
            if (cached != null && cached.modifiedMillis == modified) {
                return@withContext cached.config
            }
            val config = readConfig(path)
            cache[path] = CachedConfig(modified, config)
            config
        }

        private fun readConfig(path: Path): RagConfig {
            if (!Files.exists(path)) return RagConfig()
            return try {
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
            // Drop the memo explicitly: file systems can report the same modification time
            // for writes within one tick, which would otherwise hide this update.
            cache.remove(path)
            logger.info("RAG config saved to {}", path)
        }
    }
}
