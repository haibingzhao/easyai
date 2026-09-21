package com.easy.easyai.core.permission

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Tests for the AI risk check inserted into [PermissionService.evaluateShellPermission]:
 * when static rules fall through to ASK and a "shell.ai" ALLOW rule is present
 * (pattern = model config id), the [ShellAiRiskChecker] decides ALLOW vs ASK.
 * Failures degrade to ASK; without the rule the behavior is unchanged.
 */
class ShellAiRiskCheckTest {

    private class FakeRuleStore : PermissionRuleStore {
        override suspend fun loadRules(projectId: String): List<PermissionRule> = emptyList()
        override suspend fun saveRules(projectId: String, rules: List<PermissionRule>) {}
        override suspend fun addRule(projectId: String, rule: PermissionRule) {}
        override suspend fun deleteRule(projectId: String, permission: String, pattern: String) {}
    }

    /** Fake checker returning a canned result (or throwing) and recording calls. */
    private class FakeChecker(
        private val result: AiRiskResult? = null,
        private val error: Exception? = null
    ) : ShellAiRiskChecker {
        val calls = mutableListOf<Pair<String, String?>>()
        override suspend fun checkRisk(
            command: String,
            projectPath: Path?,
            userId: String?,
            modelConfigId: String,
            rules: List<PermissionRule>
        ): AiRiskResult {
            calls.add(command to userId)
            error?.let { throw it }
            return result ?: error("no result configured")
        }
    }

    private fun service(checker: ShellAiRiskChecker?) = PermissionService(FakeRuleStore(), null, checker)

    private val aiRule = listOf(PermissionRule("shell.ai", "model-1", PermissionAction.ALLOW))
    // An unsafe command whose whole effect is visible in the text, so it reaches the checker.
    private val args = mapOf<String, Any?>("command" to "rm -rf ./output")

    @Nested
    inner class `AI check enabled via shell-ai rule` {

        @Test
        fun `checker allows an unsafe command`() = runBlocking {
            val checker = FakeChecker(result = AiRiskResult(allowed = true, reason = "仅运行项目内脚本"))
            val result = service(checker).evaluateShellPermission(aiRule, null, args, "user-1")

            assertEquals(PermissionAction.ALLOW, result.action)
            assertEquals("shell.ai", result.permission)
            assertEquals("仅运行项目内脚本", result.reason)
            assertEquals(1, checker.calls.size)
            assertEquals("rm -rf ./output" to "user-1", checker.calls.first())
        }

        @Test
        fun `checker denies an unsafe command keeps ASK with reason`() = runBlocking {
            val checker = FakeChecker(result = AiRiskResult(allowed = false, reason = "命令将删除项目外文件"))
            val result = service(checker).evaluateShellPermission(aiRule, null, args, null)

            assertEquals(PermissionAction.ASK, result.action)
            assertEquals("shell.other", result.permission)
            assertEquals("命令将删除项目外文件", result.reason)
        }

        @Test
        fun `static allow short-circuits before AI check`() = runBlocking {
            val checker = FakeChecker(result = AiRiskResult(allowed = true))
            val rules = aiRule + PermissionRule("shell.safe", "*", PermissionAction.ALLOW)
            val readArgs = mapOf<String, Any?>("command" to "cat /tmp/data.txt")
            val result = service(checker).evaluateShellPermission(rules, null, readArgs, null)

            assertEquals(PermissionAction.ALLOW, result.action)
            assertEquals("shell.safe", result.permission)
            assertEquals(0, checker.calls.size)
        }

        @Test
        fun `no shell-ai rule keeps the legacy ASK behavior`() = runBlocking {
            val checker = FakeChecker(result = AiRiskResult(allowed = true))
            val result = service(checker).evaluateShellPermission(emptyList(), null, args, null)

            assertEquals(PermissionAction.ASK, result.action)
            assertEquals("shell.other", result.permission)
            assertNull(result.reason)
            assertEquals(0, checker.calls.size)
        }

        @Test
        fun `checker failure degrades to ASK`() = runBlocking {
            val checker = FakeChecker(error = RuntimeException("boom"))
            val result = service(checker).evaluateShellPermission(aiRule, null, args, null)

            assertEquals(PermissionAction.ASK, result.action)
        }

        @Test
        fun `null checker behaves like the feature being disabled`() = runBlocking {
            val result = service(null).evaluateShellPermission(aiRule, null, args, null)

            assertEquals(PermissionAction.ASK, result.action)
            assertEquals("shell.other", result.permission)
        }

        @Test
        fun `repeated command with same rules hits the cache`() = runBlocking {
            val checker = FakeChecker(result = AiRiskResult(allowed = true))
            val svc = service(checker)
            svc.evaluateShellPermission(aiRule, null, args, null)
            svc.evaluateShellPermission(aiRule, null, args, null)

            assertEquals(1, checker.calls.size)
        }

        @Test
        fun `missing command skips AI check`() = runBlocking {
            val checker = FakeChecker(result = AiRiskResult(allowed = true))
            val result = service(checker).evaluateShellPermission(aiRule, null, emptyMap(), null)

            assertEquals(PermissionAction.ASK, result.action)
            assertEquals(0, checker.calls.size)
        }

        @Test
        fun `script file execution skips the LLM call and still asks`() = runBlocking {
            val checker = FakeChecker(result = AiRiskResult(allowed = true))
            val scriptArgs = mapOf<String, Any?>("command" to "python3 pipeline.py")
            val result = service(checker).evaluateShellPermission(aiRule, null, scriptArgs, null)

            assertEquals(PermissionAction.ASK, result.action)
            assertEquals("shell.other", result.permission)
            assertTrue(result.reason!!.contains("未参与"), "got: ${result.reason}")
            assertEquals(0, checker.calls.size)
        }

        @Test
        fun `inline interpreter source still reaches the checker`() = runBlocking {
            val checker = FakeChecker(result = AiRiskResult(allowed = true, reason = "仅打印文本"))
            val inlineArgs = mapOf<String, Any?>("command" to """python3 -c "print(1)"""")
            val result = service(checker).evaluateShellPermission(aiRule, null, inlineArgs, null)

            assertEquals(PermissionAction.ALLOW, result.action)
            assertEquals(1, checker.calls.size)
        }
    }

    @Nested
    inner class `InterpreterScriptDetector loadsInvisibleScript` {

        private fun loads(command: String): Boolean = InterpreterScriptDetector.loadsInvisibleScript(command)

        @Test
        fun `interpreter with a script file is invisible code`() {
            assertTrue(loads("python3 pipeline.py"))
            assertTrue(loads("node build.js && echo done"))
            assertTrue(loads("/usr/bin/python3 app.py"))
            assertTrue(loads("MODE=prod python train.py"))
            assertTrue(loads("python3 -m http.server 8000"))
        }

        @Test
        fun `visible source is not flagged`() {
            assertFalse(loads("""python3 -c "print(1)"""))
            assertFalse(loads("""bash -c "rm -rf /tmp/x"""))
            assertFalse(loads("cat notes.md"))
            assertFalse(loads("python3 --version"))
        }

        @Test
        fun `commands outside the interpreter list stay with the model`() {
            // Indirect execution is not detected: a missed short-circuit only costs a call,
            // while a wrong one would lock a harmless command into ASK forever.
            assertFalse(loads("./deploy.sh"))
            assertFalse(loads("npm run build"))
            assertFalse(loads("make install"))
        }
    }

    @Nested
    inner class `DefaultPermissionSettings aiCheckModelId round-trip` {

        @Test
        fun `null model id produces no shell-ai rule`() {
            val rules = DefaultPermissionSettings.toUserRules(DefaultPermissionSettings.DEFAULT)
            assertEquals(0, rules.count { it.permission == "shell.ai" })
            assertNull(DefaultPermissionSettings.getEffectiveSettings(rules).aiCheckModelId)
        }

        @Test
        fun `model id survives toUserRules and getEffectiveSettings`() {
            val settings = DefaultPermissionSettings.DEFAULT.copy(aiCheckModelId = "model-9")
            val rules = DefaultPermissionSettings.toUserRules(settings)

            assertEquals(
                listOf(PermissionRule("shell.ai", "model-9", PermissionAction.ALLOW)),
                rules.filter { it.permission == "shell.ai" }
            )
            assertEquals("model-9", DefaultPermissionSettings.getEffectiveSettings(rules).aiCheckModelId)
        }
    }
}
