package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.ChatOptions
import com.easy.easyai.api.llm.DefaultChatOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.SamplingIntent
import com.easy.easyai.api.model.SamplingPolicy
import com.easy.easyai.core.permission.AiRiskResult
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.permission.ShellAiRiskChecker
import com.easy.easyai.core.validation.JsonExtractor
import org.slf4j.LoggerFactory
import com.easy.easyai.api.llm.SystemMessage
import com.easy.easyai.api.llm.UserMessage
import com.easy.easyai.api.llm.Prompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * LLM-backed [ShellAiRiskChecker].
 *
 * Sends the command plus a digest of the project's effective read/write
 * permission rules to a user-selected model and expects a strict JSON verdict:
 * `{"risky": true|false, "reason": "..."}`.
 *
 * Any failure (missing config, no factory, malformed output even after one re-ask) degrades to a
 * non-allowed result so the permission flow falls back to the regular ASK.
 */
class LlmShellAiRiskChecker(
    private val configStore: ModelProviderConfigStore,
    private val modelFactories: List<ChatModelFactory>
) : ShellAiRiskChecker {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val chatModelCache = ConcurrentHashMap<ModelProviderConfig, ChatModel>()

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

        // ChatModel creation builds a full provider client (connection pool + dispatcher);
        // one per shell command would leak resources. ModelProviderConfig is a data class, so
        // an edited config hashes to a new key and gets a fresh model. Superseded entries are
        // never evicted on purpose: neither the SPI nor the SDK clients expose a close hook.
        val chatModel = chatModelCache.computeIfAbsent(config) { factory.create(it) }
        // A verdict is a judgement, not a conversation: pin sampling from the row instead of
        // inheriting whatever temperature the user tuned chat with. The number travels on the
        // request, so the cached client stays keyed on the connection.
        val options = DefaultChatOptions(
            temperature = SamplingPolicy.decide(config, SamplingIntent.DETERMINISTIC).temperature
        )
        val userPrompt = buildUserPrompt(command, projectPath, rules)

        when (val first = askForVerdict(chatModel, options, userPrompt)) {
            is RoundOutcome.Decided -> return first.result

            // A round that ran out of its budget means a slow or gone model, not a badly phrased
            // answer. Re-asking pays the same wait for the same verdict, and the caller — a bash
            // command waiting to run — would sit on it: 15 s of serial LLM time doubles to 30 s
            // before the flow falls back to ASK. Only the correction round is worth that.
            RoundOutcome.TimedOut -> return AiRiskResult(allowed = false, reason = "AI 检查超时")

            is RoundOutcome.Unparsable -> {
                // One re-ask before degrading: an unparsable reply used to be thrown away outright, and
                // the cost of giving up is an ASK prompt on a command that may well be safe.
                logger.info("AI risk check produced no parsable verdict; re-asking once")
                return when (val second = askForVerdict(chatModel, options, userPrompt + VERDICT_CORRECTION)) {
                    is RoundOutcome.Decided -> second.result
                    is RoundOutcome.TimedOut -> AiRiskResult(allowed = false, reason = "AI 检查超时")
                    is RoundOutcome.Unparsable -> AiRiskResult(
                        allowed = false,
                        reason = "AI 检查输出无法解析"
                    ).also {
                        logger.warn("AI risk check still unparsable after the re-ask; treating as risky")
                    }
                }
            }
        }
    }

    /** One verdict round's answer: a verdict, or which kind of non-answer the caller has to weigh. */
    private sealed interface RoundOutcome {
        data class Decided(val result: AiRiskResult) : RoundOutcome
        data object TimedOut : RoundOutcome
        data object Unparsable : RoundOutcome
    }

    /**
     * One verdict round. [RoundOutcome.TimedOut] is the round's own budget elapsing,
     * [RoundOutcome.Unparsable] a reply that carried no parsable `{"risky": …}` payload, so the caller
     * can tell a second opinion from a second wait; a transport failure propagates to the permission
     * flow exactly as before.
     *
     * The budget is per round rather than shared: the caller may run this twice in series, and a
     * first round that ate the whole allowance would make the re-ask a guaranteed timeout instead
     * of a second opinion.
     */
    private suspend fun askForVerdict(
        chatModel: ChatModel,
        options: ChatOptions,
        userPrompt: String
    ): RoundOutcome = try {
        withTimeout(VERDICT_ROUND_TIMEOUT_MS.milliseconds) {
            askOnce(chatModel, options, userPrompt)?.let { RoundOutcome.Decided(it) } ?: RoundOutcome.Unparsable
        }
    } catch (e: TimeoutCancellationException) {
        logger.info("AI risk check round timed out after {}ms", VERDICT_ROUND_TIMEOUT_MS)
        RoundOutcome.TimedOut
    }

    private suspend fun askOnce(
        chatModel: ChatModel,
        options: ChatOptions,
        userPrompt: String
    ): AiRiskResult? {
        val prompt = Prompt(
            listOf(
                SystemMessage(SYSTEM_PROMPT),
                UserMessage(userPrompt)
            ),
            options
        )
        val response = withContext(Dispatchers.IO) {
            chatModel.call(prompt)
        }
        val json = JsonExtractor.extract(response.result?.output?.text ?: "") ?: return null
        return try {
            parseVerdictJson(json)
        } catch (e: Exception) {
            logger.warn("AI risk check JSON parse failed: {}", e.message)
            null
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
        /** Budget for one verdict round; the single re-ask gets a fresh one. */
        private const val VERDICT_ROUND_TIMEOUT_MS = 15_000L

        private val RISKY_REGEX = Regex("\"risky\"\\s*:\\s*(true|false)")
        private val REASON_REGEX = Regex("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        /**
         * Appended to the same prompt for the single re-ask. The rejected reply is not replayed —
         * the command being judged has not changed, only the output contract was violated.
         */
        private val VERDICT_CORRECTION = """

            ---
            Your previous answer could not be read as the required JSON object.
            Reply with ONLY {"risky": true|false, "reason": "..."} — no prose, no code fences.
        """.trimIndent()

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
