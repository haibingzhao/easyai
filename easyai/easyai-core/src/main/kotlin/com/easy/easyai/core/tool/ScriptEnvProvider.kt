package com.easy.easyai.core.tool

import com.easy.easyai.core.agent.AgentContext

/**
 * Provides environment variables for script execution.
 * When implemented, BashTool will inject these env vars into spawned processes,
 * enabling scripts to call back to the EasyAI backend for LLM processing.
 */
fun interface ScriptEnvProvider {
    fun getScriptEnv(context: AgentContext): Map<String, String>
}
