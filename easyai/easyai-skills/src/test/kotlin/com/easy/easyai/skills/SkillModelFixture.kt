package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillSyncState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path

/**
 * Mocked registry + catalog pair wired to the owner-shadowing rules the resolver relies on, so
 * model-view tests exercise real filtering logic against realistic binding behavior.
 */
internal class SkillModelFixture {

    companion object {
        const val SYSTEM = SkillCatalogEntry.DEFAULT_USER_ID
    }

    val config = SkillConfig(rootDir = "/.easyai-fixture-skills")
    val registry = mockk<SkillRegistry>()
    val catalog = mockk<AsyncSkillCatalogStore>()
    var rows = emptyList<SkillCatalogEntry>()

    private val entries = mutableListOf<Pair<String, SkillInfo>>()

    init {
        every { registry.visibleFor(any()) } answers {
            val owner = firstArg<String>()
            val best = LinkedHashMap<String, SkillInfo>()
            entries.filter { it.first == SYSTEM }.forEach { best.putIfAbsent(it.second.name, it.second) }
            if (owner != SYSTEM) entries.filter { it.first == owner }.forEach { best[it.second.name] = it.second }
            best.values.sortedBy { it.name }
        }
        coEvery { catalog.listByUser(any()) } answers { rows.filter { it.userId == firstArg<String>() } }
    }

    fun skill(
        name: String,
        owner: String = "alice",
        description: String = "Does $name tasks",
        tags: Set<String> = emptySet(),
    ): SkillInfo {
        val dir = Path.of(config.rootDir, SkillPaths.safeSegment(owner), name)
        return SkillInfo(name, description, dir.resolve(SkillPaths.SKILL_FILE_NAME), "Instructions for $name at $dir", tags)
    }

    /** Registers [name] under [owner] in the mocked snapshot and returns the skill. */
    fun register(
        name: String,
        owner: String = "alice",
        description: String = "Does $name tasks",
        tags: Set<String> = emptySet(),
    ): SkillInfo = skill(name, owner, description, tags).also { entries += owner to it }

    /** The owner inferred from a skill's fixture layout: `{rootDir}/{owner}/{name}/SKILL.md`. */
    fun ownerOf(skill: SkillInfo): String =
        skill.location.parent?.parent?.fileName?.toString() ?: SYSTEM

    fun row(
        skill: SkillInfo,
        user: String = ownerOf(skill),
        enabled: Boolean = true,
        state: SkillSyncState = SkillSyncState.SYNCED,
        checksum: String = "checksum"
    ): SkillCatalogEntry {
        val installDir = skill.location.parent!!
        return SkillCatalogEntry(
            id = "$user/${installDir.fileName}", name = skill.name, checksum = checksum,
            enabled = enabled, installPath = SkillPaths.canonicalize(installDir),
            rootPath = SkillPaths.canonicalize(installDir.parent!!), userId = user,
            objectKey = SkillPackageStore.KEY_DIR + "/$user/${skill.name}.zip",
            syncState = state,
            indexedChecksum = if (state == SkillSyncState.SYNCED) checksum else null
        )
    }

    fun hit(skill: SkillInfo, checksum: String = "checksum"): SkillEntry = SkillEntry(
        key = SkillEntry.keyFor(skill.name), name = skill.name, description = "untrusted index description",
        content = "untrusted index body", location = skill.location.toString(), checksum = checksum
    )
}
