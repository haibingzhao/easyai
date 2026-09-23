package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillSyncState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path

internal class SkillModelFixture {
    val project: Path = Path.of("/work/repo")
    val config = SkillConfig(paths = listOf("/shared/skills"))
    val registry = mockk<SkillRegistry>()
    val catalog = mockk<AsyncSkillCatalogStore>()
    var skills = emptyList<SkillInfo>()
    var rows = emptyList<SkillCatalogEntry>()

    init {
        every { registry.all() } answers { skills }
        coEvery { catalog.listByUser(any()) } answers { rows.filter { it.userId == firstArg<String>() } }
    }

    fun skill(name: String, project: Path? = null, description: String = "Does $name tasks"): SkillInfo {
        val root = project?.resolve(".easyai/skills") ?: Path.of("/shared/skills")
        return SkillInfo(name, description, root.resolve("$name/SKILL.md"), "Instructions for $name at $root")
    }

    fun row(
        skill: SkillInfo,
        user: String = "alice",
        enabled: Boolean = true,
        state: SkillSyncState = SkillSyncState.SYNCED
    ): SkillCatalogEntry {
        val project = SkillScopeResolver.resolve(skill, config).second
        return SkillCatalogEntry(
            id = "$user/${skill.location}", name = skill.name, checksum = "checksum",
            enabled = enabled, installPath = skill.location.parent.toString(), userId = user,
            projectHash = SkillScopeResolver.projectHashOf(project), syncState = state,
            indexProjectPath = project?.let { SkillPaths.canonicalize(it) },
            indexedChecksum = if (state == SkillSyncState.SYNCED) "checksum" else null
        )
    }

    fun hit(skill: SkillInfo): SkillEntry = SkillEntry(
        key = SkillEntry.keyFor(skill.name), name = skill.name, description = "untrusted index description",
        content = "untrusted index body", location = skill.location.toString(),
        scope = SkillScopeResolver.resolve(skill, config).first, checksum = "checksum"
    )
}
