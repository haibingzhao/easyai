package com.easy.easyai.core.agent

import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionCheckResult
import com.easy.easyai.core.permission.PermissionService
import com.easy.easyai.core.message.MessageConverter
import com.easy.easyai.core.prompt.PromptTemplateService
import com.easy.easyai.core.tool.ToolExecutionEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A background run has nobody to answer a permission prompt. An ASK decision must become a Blocked
 * tool call the model can report, never a PermissionRequest the loop would wait on forever.
 */
class UnattendedPermissionTest {

    private val permission: PermissionService = mockk(relaxed = true)

    private fun service() = DefaultAgentService(
        chatModelFactories = emptyList(),
        messageConverter = mockk<MessageConverter>(relaxed = true),
        toolExecutor = mockk<ToolExecutionEngine>(relaxed = true),
        promptTemplateService = mockk<PromptTemplateService>(relaxed = true),
        permissionService = permission
    )

    private fun giveAskDecision() {
        coEvery {
            permission.evaluateForToolCall(
                any<String>(),
                any<String>(),
                any<Map<String, Any?>>(),
                any(),
                any(),
                any(),
                any<ProjectKind>(),
                any<Collection<String>>()
            )
        } returns PermissionCheckResult(
            action = PermissionAction.ASK,
            permission = "tool.execute",
            pattern = "bash:*"
        )
    }

    private fun context(unattended: Boolean, parentAgentId: String? = null) = BeforeToolCallContext(
        toolCallId = "call-1",
        toolName = "bash",
        arguments = mapOf("command" to "ls"),
        projectId = "project-1",
        userId = "alice",
        parentAgentId = parentAgentId,
        unattended = unattended
    )

    @Test
    fun `an unattended ASK is blocked with a reason instead of prompting`() = runTest {
        giveAskDecision()
        val result = service().invoke(context(unattended = true))
        val block = assertIs<BeforeToolCallResult.Block>(result)
        assertTrue(block.reason.contains("Unattended run"))
        assertTrue(block.reason.contains("tool.execute:bash:*"))
        assertTrue(block.reason.contains("report this source as unavailable"))
    }

    @Test
    fun `an attended main-agent ASK still requests permission`() = runTest {
        giveAskDecision()
        val result = service().invoke(context(unattended = false))
        val request = assertIs<BeforeToolCallResult.PermissionRequest>(result)
        assertEquals("tool.execute", request.permission)
        assertEquals("bash:*", request.pattern)
    }

    @Test
    fun `an allow decision is untouched in both modes`() = runTest {
        coEvery {
            permission.evaluateForToolCall(
                any<String>(),
                any<String>(),
                any<Map<String, Any?>>(),
                any(),
                any(),
                any(),
                any<ProjectKind>(),
                any<Collection<String>>()
            )
        } returns PermissionCheckResult(action = PermissionAction.ALLOW, permission = "tool.execute", pattern = "*")
        assertEquals(BeforeToolCallResult.Allow, service().invoke(context(unattended = true)))
    }
}
