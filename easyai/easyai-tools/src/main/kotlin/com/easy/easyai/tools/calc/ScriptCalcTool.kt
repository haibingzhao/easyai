package com.easy.easyai.tools.calc

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.time.Duration.Companion.seconds

/**
 * Parameters for [ScriptCalcTool].
 */
data class ScriptCalcParams(
    /** Groovy script to execute in-memory for numerical or date/time calculations. */
    val script: String
)

/**
 * Executes Groovy scripts in-memory for safe numerical and date/time calculations.
 *
 * The script runs inside a sandboxed [groovy.lang.GroovyShell] with restricted imports
 * (no file I/O, no network, no process spawning) and a hard timeout, so it is strictly
 * safer than the bash tool for any computation task.
 */
class ScriptCalcTool(metadata: ToolMetadata) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val executionMode = ToolExecutionMode.PARALLEL
    override fun parameterType() = ScriptCalcParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        val script = args["script"] as? String
        if (script.isNullOrBlank()) {
            return@withContext errorResult("Error: 'script' parameter is required and must not be empty")
        }

        onUpdate(ToolUpdate.Progress("Calculating…"))

        try {
            val result = withTimeout(TIMEOUT_SECONDS.seconds) {
                val shell = GroovySandbox.createSecureShell()
                val outputCapture = StringWriter()
                shell.context.setVariable("out", PrintWriter(outputCapture))
                val evalResult = shell.evaluate(script)
                val stdout = outputCapture.toString().trimEnd('\n')
                // Prefer stdout (println output) over the last expression value
                stdout.ifEmpty { evalResult?.toString() ?: "null" }
            }
            ToolResult(content = listOf(TextContent(result)))
        } catch (_: TimeoutCancellationException) {
            errorResult("Script execution timed out after ${TIMEOUT_SECONDS}s")
        } catch (e: org.codehaus.groovy.control.CompilationFailedException) {
            logger.debug("Script compilation error: {}", e.message)
            // CompilationFailedException.message contains line/column details — keep full text
            val detail = e.message?.trim() ?: "unknown compilation error"
            errorResult("Script syntax error:\n$detail")
        } catch (e: groovy.lang.GroovyRuntimeException) {
            logger.debug("Script runtime error: {}", e.message)
            errorResult("Script runtime error: ${e.message?.lines()?.firstOrNull() ?: e.message}")
        } catch (e: SecurityException) {
            logger.warn("Script security violation: {}", e.message)
            errorResult("Script security violation: ${e.message}")
        } catch (e: Exception) {
            logger.warn("Script execution failed: {}", e.message)
            errorResult("Script execution error: ${e.message}")
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 10
    }
}
