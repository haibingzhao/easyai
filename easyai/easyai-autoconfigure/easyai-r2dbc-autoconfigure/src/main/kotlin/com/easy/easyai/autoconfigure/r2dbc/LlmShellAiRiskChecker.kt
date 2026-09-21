package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.core.permission.AiRiskResult
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.permission.ShellAiRiskChecker
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * LLM-backed [ShellAiRiskChecker].
 *
 * Sends the command plus a digest of the project's effective read/write
 * permission rules to a user-selected model and expects a strict JSON verdict:
 * `{"risky": true|false, "reason": "..."}`.
 *
 * Any failure (missing config, no factory, malformed output) degrades to a
 * non-allowed result so the permission flow falls back to the regular ASK.
 */
class LlmShellAiRiskChecker(
    private val configStore: ModelProviderConfigStore,
    private val modelFactories: List<ChatModelFactory>
) : ShellAiRiskChecker {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun checkRisk(
        command: String,
        projectPath: Path?,
        userId: String?,
        modelConfigId: String,
        rules: List<PermissionRule>
    ): AiRiskResult {
        val effectiveUserId = userId ?: "system"
        val config = configStore.getConfig(modelConfigId, effectiveUserId)
        if (config == null) {
            logger.warn("AI risk check model config '{}' not found for user {}", modelConfigId, effectiveUserId)
            return AiRiskResult(allowed = false, reason = "AI 检查模型配置不存在")
        }
        val factory = modelFactories.firstOrNull { it.supports(config.protocol) }
        if (factory == null) {
            logger.warn("No ChatModelFactory for protocol {}", config.protocol)
            return AiRiskResult(allowed = false, reason = "AI 检查模型协议不受支持")
        }

        val chatModel = factory.create(config)
        val prompt = Prompt(
            listOf(
                SystemMessage(SYSTEM_PROMPT),
                UserMessage(buildUserPrompt(command, projectPath, rules))
            )
        )

        val response = withContext(Dispatchers.IO) {
            chatModel.call(prompt)
        }
        val content = response.result?.output?.text ?: ""
        return parseVerdict(content)
    }

    /** Extract the JSON payload and map it to an [AiRiskResult]; malformed output is treated as risky. */
    private fun parseVerdict(text: String): AiRiskResult {
        val json = extractJsonObject(text)
        if (json == null) {
            logger.warn("AI risk check produced no JSON payload; treating as risky")
            return AiRiskResult(allowed = false, reason = "AI 检查输出无法解析")
        }
        return try {
            parseVerdictJson(json)
        } catch (e: Exception) {
            logger.warn("AI risk check JSON parse failed: {}", e.message)
            AiRiskResult(allowed = false, reason = "AI 检查输出无法解析")
        }
    }

    /** Regex parser for the tiny `{"risky": bool, "reason": string}` payload. */
    private fun parseVerdictJson(json: String): AiRiskResult {
        val risky = RISKY_REGEX.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("missing risky field")
        val reason = REASON_REGEX.find(json)?.groupValues?.get(1)
            ?.unescapeJson()
        return AiRiskResult(allowed = !risky, reason = reason)
    }

    /** Resolve the basic JSON string escapes used in the reason field. */
    private fun String.unescapeJson(): String {
        val sb = StringBuilder(length)
        var i = 0
        while (i < this.length) {
            val ch = this[i]
            if (ch == '\\' && i + 1 < this.length) {
                when (val next = this[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'u' -> if (i + 5 < this.length) {
                        sb.append(this.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    } else sb.append(next)
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    /** Locate the outermost JSON object; tolerates stray prose around the payload. */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start !in 0..<end) return null
        return text.substring(start, end + 1)
    }

    private fun buildUserPrompt(command: String, projectPath: Path?, rules: List<PermissionRule>): String {
        val sb = StringBuilder()
        sb.appendLine("<command>")
        sb.appendLine(command)
        sb.appendLine("</command>")
        if (projectPath != null) {
            sb.appendLine("<project_path>$projectPath</project_path>")
        }
        sb.appendLine("<permission_rules>")
        // The shell.ai rule itself is not part of the risk context.
        rules.filter { it.permission != "shell.ai" }.forEach { rule ->
            sb.appendLine("- ${rule.permission}: ${rule.pattern} -> ${rule.action}")
        }
        sb.appendLine("</permission_rules>")
        return sb.toString()
    }

    private companion object {
        private val RISKY_REGEX = Regex("\"risky\"\\s*:\\s*(true|false)")
        private val REASON_REGEX = Regex("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        val SYSTEM_PROMPT = """
            You are a shell command security reviewer for an AI coding assistant.
            Given a shell command and the user's effective permission rules, decide whether
            executing the command is risky.

            Judgement criteria:
            - Not risky (risky=false) only when every effect of the command stays inside the
              allowed scope: reads confined to readable locations (file.read.* rules,
              including the project directory when file.read.project is ALLOW), writes
              confined to writable locations (file.write.* rules), or the command matches
              an explicitly allowed shell.other pattern.
            - Risky (risky=true) when the command reads/writes outside the allowed scope,
              deletes or overwrites data outside the project, sends data over the network,
              downloads and executes code, escalates privileges, installs or removes system
              packages, or its side effects cannot be determined with reasonable confidence.
            - Network access itself is not risky when it is clearly needed by an allowed
              build/package command (e.g. maven/npm fetching dependencies); commands that
              pipe downloaded content straight into an interpreter or shell are always risky.
            - Judge from the command text only. When the command runs code that is not
              written out there — a script file, an installed module, a build target, or a
              program launched through a package runner — its effects cannot be verified:
              report risky=true and say the executed code is unknown. Never infer behaviour
              from a file name or directory. Judge on its merits only when the source really
              is visible in the command (`python3 -c "..."`, `bash -c "..."`).

            Respond with ONLY a JSON object, no markdown fences, no extra prose:
            {"risky": true|false, "reason": "<brief explanation, in Chinese>"}
        """.trimIndent()
    }
}
