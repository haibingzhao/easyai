package com.easy.easyai.web.service

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the internal script LLM processing feature.
 *
 * Prefix: easyai.script-llm
 *
 * When enabled, scripts executed via BashTool receive environment variables
 * (EASYAI_SCRIPT_TOKEN, EASYAI_BACKEND_URL, EASYAI_MODEL_CONFIG_ID) that allow
 * them to call back to the EasyAI backend for LLM processing without API keys.
 */
@ConfigurationProperties(prefix = "easyai.script-llm")
data class ScriptLlmProperties(
    /** Whether the script LLM feature is enabled. */
    var enabled: Boolean = false
)
