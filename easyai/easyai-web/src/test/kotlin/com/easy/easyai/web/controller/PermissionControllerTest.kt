package com.easy.easyai.web.controller

import com.easy.easyai.agent.registry.ToolRegistry
import com.easy.easyai.auth.group.GroupClaims
import com.easy.easyai.core.model.ProjectInfo
import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.permission.PermissionRuleStore
import com.easy.easyai.core.permission.PermissionScope
import com.easy.easyai.core.permission.PermissionService
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.web.model.AddRuleRequest
import com.easy.easyai.web.model.PermissionRuleDto
import com.easy.easyai.web.model.SaveRulesRequest
import com.easy.easyai.web.model.UpdateSettingRequest
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import reactor.util.context.Context
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PermissionController] scope targeting and write gate.
 *
 * A temporary workspace maps to the project-less default bucket, which is the group's shared bucket
 * for a family login. These tests pin that members can read that bucket but cannot rewrite it by
 * presenting their own temp project id, that unknown/foreign project ids are 404 instead of falling
 * back to the raw id, and that the DTO tells the UI whether the bucket is editable.
 */
class PermissionControllerTest {

    @TempDir
    lateinit var root: Path

    private class InMemoryProjectStore : AsyncProjectStore {
        /** Project id to its owner and row. */
        val rows = mutableMapOf<String, Pair<String, ProjectInfo>>()

        override suspend fun save(project: ProjectInfo, userId: String) {
            rows[project.id] = userId to project
        }

        override suspend fun saveIfAbsent(project: ProjectInfo, userId: String): Boolean {
            if (rows.containsKey(project.id)) return false
            rows[project.id] = userId to project
            return true
        }

        override suspend fun findById(id: String, userId: String): ProjectInfo? =
            rows[id]?.takeIf { it.first == userId }?.second

        override suspend fun findByPath(path: String, userId: String): ProjectInfo? =
            rows.values.firstOrNull { it.first == userId && it.second.path == path }?.second

        override fun findAll(
            limit: Int?,
            search: String?,
            userId: String,
            includeTemp: Boolean
        ): Flow<ProjectInfo> = emptyFlow()

        override suspend fun delete(id: String, userId: String) {
            if (rows[id]?.first == userId) rows.remove(id)
        }
    }

    private class InMemoryRuleStore : PermissionRuleStore {
        val rules = mutableMapOf<String, MutableList<PermissionRule>>()

        override suspend fun loadRules(projectId: String): List<PermissionRule> =
            rules[projectId].orEmpty()

        override suspend fun saveRules(projectId: String, rules: List<PermissionRule>) {
            this.rules[projectId] = rules.toMutableList()
        }

        override suspend fun addRule(projectId: String, rule: PermissionRule) {
            rules.getOrPut(projectId) { mutableListOf() }.add(rule)
        }

        override suspend fun deleteRule(projectId: String, permission: String, pattern: String) {
            rules[projectId]?.removeIf { it.permission == permission && it.pattern == pattern }
        }

        override suspend fun deleteProjectRules(projectId: String) {
            rules.remove(projectId)
        }
    }

    private val projectStore = InMemoryProjectStore()
    private val ruleStore = InMemoryRuleStore()
    private val controller = PermissionController(
        PermissionService(ruleStore, null),
        ruleStore,
        mockk<ToolRegistry>(),
        projectStore
    )

    private val owner = GroupClaims(owners = listOf("grp_1"), groupId = "fam", groupUserId = "grp_1", isGroupOwner = true)
    private val member = GroupClaims(owners = listOf("grp_1"), groupId = "fam", groupUserId = "grp_1", isGroupOwner = false)

    private fun asUser(userId: String, claims: GroupClaims? = null): Context =
        ReactiveSecurityContextHolder.withAuthentication(
            UsernamePasswordAuthenticationToken(userId, "", emptyList<GrantedAuthority>()).apply {
                details = claims
            }
        )

    private fun seedTemp(id: String, userId: String, path: Path = Path.of("/tmp", id)) {
        projectStore.rows[id] = userId to ProjectInfo(
            id = id,
            name = "temp",
            path = path.toString(),
            kind = ProjectKind.TEMP
        )
    }

    @Nested
    inner class `write gate for a shared default bucket` {

        @Test
        fun `a member cannot patch a temporary workspace setting`() = runTest {
            seedTemp("tmp-bob", "bob")

            val error = assertFailsWith<ResponseStatusException> {
                controller.updateSetting("tmp-bob", UpdateSettingRequest("executeAllCommands", true))
                    .contextWrite(asUser("bob", member))
                    .awaitSingle()
            }

            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
            assertTrue(ruleStore.rules.isEmpty())
        }

        @Test
        fun `a member cannot replace rules through a temporary workspace`() = runTest {
            seedTemp("tmp-bob", "bob")

            val error = assertFailsWith<ResponseStatusException> {
                controller.saveRules(
                    "tmp-bob",
                    SaveRulesRequest(listOf(PermissionRuleDto("shell.all", "*", "ALLOW")))
                ).contextWrite(asUser("bob", member)).awaitSingle()
            }

            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
            assertTrue(ruleStore.rules.isEmpty())
        }

        @Test
        fun `a member cannot add rules through a temporary workspace`() = runTest {
            seedTemp("tmp-bob", "bob")

            val error = assertFailsWith<ResponseStatusException> {
                controller.addRule("tmp-bob", AddRuleRequest("shell.all", "*", "ALLOW"))
                    .contextWrite(asUser("bob", member))
                    .awaitSingle()
            }

            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
            assertTrue(ruleStore.rules.isEmpty())
        }

        @Test
        fun `a member cannot delete rules through a temporary workspace`() = runTest {
            seedTemp("tmp-bob", "bob")
            val groupScope = PermissionScope.groupDefault("grp_1")
            ruleStore.rules[groupScope] = mutableListOf(
                PermissionRule("shell.other", "rm -rf /", PermissionAction.DENY)
            )

            val error = assertFailsWith<ResponseStatusException> {
                controller.deleteRule("tmp-bob", "shell.other", "rm -rf /")
                    .contextWrite(asUser("bob", member))
                    .awaitSingle()
            }

            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
            assertEquals(1, ruleStore.rules.getValue(groupScope).size)
        }

        @Test
        fun `the group owner writes the shared bucket through their temporary workspace`() = runTest {
            seedTemp("tmp-alice", "alice")

            controller.updateSetting("tmp-alice", UpdateSettingRequest("useMcp", false))
                .contextWrite(asUser("alice", owner))
                .awaitSingle()

            assertTrue(ruleStore.rules.getValue(PermissionScope.groupDefault("grp_1")).any {
                it.permission == "mcp.use" && it.pattern == "*" && it.action == PermissionAction.DENY
            })
        }

        @Test
        fun `a member cannot patch the default settings route`() = runTest {
            val error = assertFailsWith<ResponseStatusException> {
                controller.updateDefaultSetting(UpdateSettingRequest("executeAllCommands", true))
                    .contextWrite(asUser("bob", member))
                    .awaitSingle()
            }

            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
            assertTrue(ruleStore.rules.isEmpty())
        }

        @Test
        fun `a group-less login writes its personal default`() = runTest {
            controller.updateDefaultSetting(UpdateSettingRequest("useMcp", false))
                .contextWrite(asUser("carol"))
                .awaitSingle()

            assertTrue(ruleStore.rules.getValue(PermissionScope.userDefault("carol")).any {
                it.permission == "mcp.use" && it.action == PermissionAction.DENY
            })
        }
    }

    @Nested
    inner class `project ownership` {

        @Test
        fun `an unknown project id is 404 instead of reading the raw bucket`() = runTest {
            val error = assertFailsWith<ResponseStatusException> {
                controller.getRules("missing-project")
                    .contextWrite(asUser("bob", member))
                    .awaitSingle()
            }

            assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
        }

        @Test
        fun `another user's project id cannot be read`() = runTest {
            projectStore.rows["alice-project"] = "alice" to ProjectInfo(
                id = "alice-project",
                name = "project",
                path = root.resolve("alice-project").toString()
            )
            ruleStore.rules["alice-project"] = mutableListOf(
                PermissionRule("shell.all", "*", PermissionAction.ALLOW)
            )

            val error = assertFailsWith<ResponseStatusException> {
                controller.getSettings("alice-project")
                    .contextWrite(asUser("bob", member))
                    .awaitSingle()
            }

            assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
        }

        @Test
        fun `another user's project id cannot be written`() = runTest {
            projectStore.rows["alice-project"] = "alice" to ProjectInfo(
                id = "alice-project",
                name = "project",
                path = root.resolve("alice-project").toString()
            )

            val error = assertFailsWith<ResponseStatusException> {
                controller.saveRules(
                    "alice-project",
                    SaveRulesRequest(listOf(PermissionRuleDto("shell.all", "*", "ALLOW")))
                ).contextWrite(asUser("bob", member)).awaitSingle()
            }

            assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
            assertTrue(ruleStore.rules.isEmpty())
        }
    }

    @Nested
    inner class `editable flag` {

        @Test
        fun `a member sees the shared bucket as read-only`() = runTest {
            seedTemp("tmp-bob", "bob")

            val settings = controller.getSettings("tmp-bob")
                .contextWrite(asUser("bob", member))
                .awaitSingle()

            assertTrue(settings.sharedWithGroup)
            assertFalse(settings.canEdit)
        }

        @Test
        fun `the group owner sees the shared bucket as editable`() = runTest {
            seedTemp("tmp-alice", "alice")

            val settings = controller.getSettings("tmp-alice")
                .contextWrite(asUser("alice", owner))
                .awaitSingle()

            assertTrue(settings.sharedWithGroup)
            assertTrue(settings.canEdit)
        }

        @Test
        fun `a group-less default bucket is editable`() = runTest {
            val settings = controller.getDefaultSettings()
                .contextWrite(asUser("carol"))
                .awaitSingle()

            assertFalse(settings.sharedWithGroup)
            assertTrue(settings.canEdit)
        }
    }

    @Nested
    inner class `file access uses the evaluated scope` {

        @Test
        fun `a temporary workspace honors read-other-paths from the default bucket`() = runTest {
            val workspace = root.resolve("workspace")
            Files.createDirectories(workspace)
            val outside = root.resolve("outside.txt")
            Files.writeString(outside, "hello")
            seedTemp("tmp-carol", "carol", workspace)
            ruleStore.rules[PermissionScope.userDefault("carol")] = mutableListOf(
                PermissionRule("file.read.other", outside.toString(), PermissionAction.ALLOW)
            )

            val content = controller.readFileContent(outside.toString(), "tmp-carol")
                .contextWrite(asUser("carol"))
                .awaitSingle()

            assertEquals("hello", content["content"])
        }
    }
}
