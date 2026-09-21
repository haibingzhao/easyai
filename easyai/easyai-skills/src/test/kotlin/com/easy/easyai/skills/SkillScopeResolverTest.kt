package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillScope
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SkillScopeResolver]: granularity is a pure function of the install path, so these
 * cases pin down the two shapes that decide which RAG slice a skill is searched in.
 */
class SkillScopeResolverTest {

    private val config = SkillConfig()
    private val home: Path = Path.of(System.getProperty("user.home"))

    private fun row(installPath: String, name: String = "pdf-report") = SkillCatalogEntry(
        id = "id-$installPath",
        name = name,
        checksum = "a".repeat(64),
        installPath = installPath
    )

    @Test
    fun `a skill under the home easyai directory is GLOBAL`() {
        val (scope, projectPath) = SkillScopeResolver.resolve(row("$home/.easyai/skills/pdf-report"), config)

        assertEquals(SkillScope.GLOBAL, scope)
        assertNull(projectPath, "a global skill has no project slice to address")
    }

    @Test
    fun `a skill under the shared agents directory is GLOBAL`() {
        val (scope, projectPath) = SkillScopeResolver.resolve(row("$home/.agents/skills/pdf-report"), config)

        assertEquals(SkillScope.GLOBAL, scope)
        assertNull(projectPath)
    }

    @Test
    fun `a project install recovers the workspace root from the install path`() {
        val (scope, projectPath) = SkillScopeResolver.resolve(row("/p/q/.agents/skills/pdf-report"), config)

        assertEquals(SkillScope.PROJECT, scope)
        assertEquals(Path.of("/p/q"), projectPath)
    }

    @Test
    fun `a nested workspace path keeps every parent of the skill directory root`() {
        val (scope, projectPath) = SkillScopeResolver.resolve(row("/work/team/repo/.easyai/skills/pdf-report"), config)

        assertEquals(SkillScope.PROJECT, scope)
        assertEquals(Path.of("/work/team/repo"), projectPath)
    }

    @Test
    fun `a path that matches no known skill directory stays GLOBAL rather than vanishing`() {
        val (scope, projectPath) = SkillScopeResolver.resolve(row("/opt/share/other/pdf-report"), config)

        assertEquals(SkillScope.GLOBAL, scope, "an unclassifiable skill must remain discoverable")
        assertNull(projectPath)
    }

    @Test
    fun `a home path that is no skill root still falls back to GLOBAL`() {
        // Same depth as a global install but under a directory nobody scans for skills: the
        // conservative fallback keeps it discoverable instead of inventing a project slice.
        val (scope, projectPath) = SkillScopeResolver.resolve(row("$home/documents/pdf-report"), config)

        assertEquals(SkillScope.GLOBAL, scope)
        assertNull(projectPath)
    }

    @Test
    fun `the discovered-skill overload derives the directory from the skill file location`() {
        val skill = SkillInfo(
            name = "pdf-report",
            description = "d",
            location = Path.of("/p/q/.easyai/skills/pdf-report/SKILL.md"),
            content = "body"
        )

        val (scope, projectPath) = SkillScopeResolver.resolve(skill, config)

        assertEquals(SkillScope.PROJECT, scope)
        assertEquals(Path.of("/p/q"), projectPath)
    }

    @Test
    fun `custom home skill directories are honoured`() {
        val custom = SkillConfig(homeSkillDirs = listOf(".custom/skills"))

        val (scope, projectPath) = SkillScopeResolver.resolve(row("/p/q/.custom/skills/pdf-report"), custom)

        assertEquals(SkillScope.PROJECT, scope)
        assertEquals(Path.of("/p/q"), projectPath)
    }

    @Test
    fun `the granularity token of a global skill is the empty string`() {
        assertEquals(SkillCatalogEntry.GLOBAL_HASH, SkillScopeResolver.projectHashOf(null))
    }

    @Test
    fun `the granularity token is stable per path and different across paths`() {
        val direct = SkillScopeResolver.projectHashOf(Path.of("/work/repo"))
        val samePathSpelledDifferently = SkillScopeResolver.projectHashOf(Path.of("/work/other/../repo"))

        assertEquals(16, direct.length, "half a SHA-256 block: wide enough to survive a whole fleet of projects")
        assertEquals(direct, samePathSpelledDifferently, "normalization before hashing, or the same project claims twice")
        assertTrue(SkillScopeResolver.projectHashOf(Path.of("/work/repo-other")) != direct)
    }

    @Test
    fun `candidate roots walk from the request up to the global fallback`() {
        val roots = SkillScopeResolver.candidateRoots(Path.of("/work/repo/module"))

        assertEquals(listOf(Path.of("/work/repo/module"), Path.of("/work/repo"), Path.of("/work"), Path.of("/"), null), roots)
        assertEquals(listOf(null), SkillScopeResolver.candidateRoots(null), "no request project means the global view only")
    }
}
