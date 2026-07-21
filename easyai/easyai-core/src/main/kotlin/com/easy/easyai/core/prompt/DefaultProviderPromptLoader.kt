package com.easy.easyai.core.prompt

import org.slf4j.LoggerFactory

/**
 * Default implementation that loads prompt templates from classpath resources.
 * Caches loaded prompts to avoid repeated I/O.
 */
class DefaultProviderPromptLoader : ProviderPromptLoader {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val prompts: Map<String, String> by lazy {
        mapOf(
            "ANTHROPIC" to loadResource("prompts/anthropic.txt"),
            "OPENAI" to loadResource("prompts/codex.txt")
        ).also { logger.info("Loaded {} provider prompt templates", it.size) }
    }

    override fun getPromptForProtocol(protocol: String): String {
        return prompts[protocol] ?: prompts.getValue("OPENAI").also {
            logger.warn("No prompt template for protocol '{}', falling back to OPENAI", protocol)
        }
    }

    private fun loadResource(path: String): String {
        val resource = javaClass.classLoader.getResource(path)
            ?: throw IllegalStateException("Prompt resource not found: $path")
        return resource.readText(Charsets.UTF_8)
    }
}
