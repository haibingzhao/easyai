package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillScope
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillScopeResolverTest {

    private val config = SkillConfig()
    private val home: Path = Path.of(System.getProperty("user.home"))

    private fun row(installPath: String, name: String = "pdf-report", config: SkillConfig = this.config): SkillCatalogEntry {
        val project = SkillPaths.canonicalizeOrNull(installPath)?.let {
            SkillScopeResolver.classify(Path.of(it), config)?.second
        }
        return SkillCatalogEntry(
            id = "id-$installPath", name = name, checksum = "a".repeat(64), installPath = installPath,
            projectHash = SkillScopeResolver.projectHashOf(project),
            indexProjectPath = project?.let { SkillPaths.canonicalize(it) }
        )
    }

    @Nested
    inner class DirectoryBoundaries {

        @Test
        fun `home skill directories are GLOBAL including nested folders`() {
            for (dir in config.homeSkillDirs) {
                assertEquals(
                    SkillScope.GLOBAL to null,
                    SkillScopeResolver.resolve(row("$home/$dir/group/folder"), config)
                )
            }
        }

        @Test
        fun `scope is independent of the declared skill name and nested folders`() {
            for (dir in config.homeSkillDirs) {
                for (folder in listOf("different-name", "group/subgroup/different-name")) {
                    val entry = row("/work/team/repo/$dir/$folder")
                    assertEquals(
                        SkillScope.PROJECT to Path.of("/work/team/repo"),
                        SkillScopeResolver.resolve(entry, config)
                    )
                }
            }
        }

        @Test
        fun `nearest actual skill directory boundary identifies the project`() {
            val entry = row("/work/repo/.easyai/skills/nested-project/.agents/skills/group/folder")
            assertEquals(
                SkillScope.PROJECT to Path.of("/work/repo/.easyai/skills/nested-project"),
                SkillScopeResolver.resolve(entry, config)
            )
        }

        @Test
        fun `the discovered skill overload uses the file directory not the name`() {
            val skill = SkillInfo(
                name = "pdf-report",
                location = Path.of("/p/q/.easyai/skills/group/other-name/SKILL.md"),
                content = "body"
            )
            assertEquals(SkillScope.PROJECT to Path.of("/p/q"), SkillScopeResolver.resolve(skill, config))
        }

        @Test
        fun `a SKILL file directly in the configured project root remains project scoped`() {
            val skill = SkillInfo("root-skill", location = Path.of("/p/q/.easyai/skills/SKILL.md"), content = "body")
            assertEquals(SkillScope.PROJECT to Path.of("/p/q"), SkillScopeResolver.resolve(skill, config))
        }

        @Test
        fun `custom skill directory names are honoured`() {
            val custom = SkillConfig(homeSkillDirs = listOf(".custom/skills"))
            assertEquals(
                SkillScope.PROJECT to Path.of("/p/q"),
                SkillScopeResolver.resolve(row("/p/q/.custom/skills/group/folder", config = custom), custom)
            )
        }

        @Test
        fun `scope classification normalizes directory segments`() {
            assertEquals(
                SkillScope.PROJECT to Path.of("/p/q"),
                SkillScopeResolver.resolve(row("/p/other/../q/.easyai/skills/group/../folder"), config)
            )
        }
    }

    @Nested
    inner class SharedSources {

        @Test
        fun `explicit absolute relative and home expanded paths are GLOBAL`() {
            val shared = config.copy(workDir = "/server", paths = listOf("/opt/shared", "relative", "~/custom-skills"))
            for (path in listOf("/opt/shared/group/folder", "/server/relative/folder", "$home/custom-skills/folder")) {
                assertEquals(SkillScope.GLOBAL to null, SkillScopeResolver.resolve(row(path), shared))
            }
        }

        @Test
        fun `explicit paths inside a project skill tree are not shared`() {
            for (configured in listOf("/work", "/work/repo/.easyai/skills", "/work/repo/.easyai/skills/group")) {
                val shared = config.copy(paths = listOf(configured))
                assertEquals(
                    SkillScope.PROJECT to Path.of("/work/repo"),
                    SkillScopeResolver.resolve(row("/work/repo/.easyai/skills/group/folder"), shared)
                )
            }
        }

        @Test
        fun `relative config paths inside the work project remain PROJECT`() {
            val configured = config.copy(workDir = "/work/repo", paths = listOf(".easyai/skills"))
            assertEquals(
                SkillScope.PROJECT to Path.of("/work/repo"),
                SkillScopeResolver.resolve(row("/work/repo/.easyai/skills/group/folder"), configured)
            )
        }

        @Test
        fun `prefix lookalikes do not match home project or explicit shared roots`() {
            val shared = config.copy(paths = listOf("/opt/shared"))
            for (path in listOf("$home/.easyai/skills-other/folder", "/p/.agents/skills-other/folder", "/opt/shared-other/folder")) {
                assertNull(SkillScopeResolver.classify(row(path), shared), path)
            }
            val otherHome = Path.of("${home}-other")
            assertEquals(
                SkillScope.PROJECT to otherHome,
                SkillScopeResolver.resolve(row("$otherHome/.easyai/skills/folder"), shared)
            )
        }

        @Test
        fun `unknown locations are unclassified and resolve fails explicitly`() {
            for (path in listOf("/opt/other/folder", "$home/documents/pdf-report", "", "bad\u0000path")) {
                val entry = row(path)
                assertNull(SkillScopeResolver.classify(entry, config))
                val failure = assertFailsWith<IllegalArgumentException> { SkillScopeResolver.resolve(entry, config) }
                assertTrue(failure.message.orEmpty().contains("Unknown skill install root"))
            }
        }

        @Test
        fun `unknown discovered skill is not implicitly GLOBAL`() {
            val skill = SkillInfo("pdf", location = Path.of("/unknown/pdf/SKILL.md"), content = "body")
            assertNull(SkillScopeResolver.classify(skill, config))
            assertFailsWith<IllegalArgumentException> { SkillScopeResolver.resolve(skill, config) }
        }
    }

    @Nested
    inner class CatalogIdentity {

        @Test
        fun `current PROJECT identity requires both matching hash and canonical index address`() {
            val entry = row("/work/repo/.easyai/skills/pdf")
            assertEquals(SkillScope.PROJECT to Path.of("/work/repo"), SkillScopeResolver.resolve(entry, config))
            for (invalid in listOf(
                entry.copy(projectHash = ""),
                entry.copy(indexProjectPath = null),
                entry.copy(indexProjectPath = "/work/other"),
                entry.copy(indexProjectPath = "/work/other/../repo")
            )) {
                assertFailsWith<IllegalArgumentException> { SkillScopeResolver.classify(invalid, config) }
            }
            assertEquals(
                SkillScope.PROJECT to Path.of("/work/repo"),
                SkillScopeResolver.classify(Path.of(entry.installPath), config),
                "raw path classification must not depend on catalog identity"
            )
        }

        @Test
        fun `GLOBAL identity cannot retain a PROJECT address or token`() {
            val entry = row("$home/.easyai/skills/pdf")
            assertEquals(SkillScope.GLOBAL to null, SkillScopeResolver.resolve(entry, config))
            for (invalid in listOf(
                entry.copy(projectHash = SkillScopeResolver.projectHashOf(Path.of("/work/repo"))),
                entry.copy(indexProjectPath = "/work/repo")
            )) {
                assertFailsWith<IllegalArgumentException> { SkillScopeResolver.resolve(invalid, config) }
            }
        }
    }

    @Nested
    inner class RequestIdentity {

        @Test
        fun `the granularity token of a global skill is the empty string`() {
            assertEquals(SkillCatalogEntry.GLOBAL_HASH, SkillScopeResolver.projectHashOf(null))
        }

        @Test
        fun `the granularity token is stable per normalized path and different across paths`() {
            val direct = SkillScopeResolver.projectHashOf(Path.of("/work/repo"))
            assertEquals(16, direct.length)
            assertEquals(direct, SkillScopeResolver.projectHashOf(Path.of("/work/other/../repo")))
            assertTrue(SkillScopeResolver.projectHashOf(Path.of("/work/repo-other")) != direct)
        }

        @Test
        fun `candidate roots include only the normalized current project and GLOBAL`() {
            assertEquals(
                listOf(Path.of("/work/repo/module"), null),
                SkillScopeResolver.candidateRoots(Path.of("/work/repo/other/../module"))
            )
            assertEquals(listOf(null), SkillScopeResolver.candidateRoots(null))
        }
    }
}
