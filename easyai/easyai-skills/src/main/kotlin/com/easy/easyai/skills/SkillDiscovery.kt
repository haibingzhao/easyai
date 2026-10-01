package com.easy.easyai.skills

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * Filesystem discovery of owner skill roots. The only scan shape is
 * `{ownerRoot}/{skillName}/SKILL.md` — there is no home/project/workdir walk any more, and nothing
 * nested below a skill directory is a skill: that is the skill's own payload (scripts, references,
 * vendored trees), which the package codec copies verbatim without interpreting.
 */
interface SkillDiscovery {
    fun discoverOwnerRoot(ownerRoot: Path): List<SkillInfo>
}

class DefaultSkillDiscovery : SkillDiscovery {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun discoverOwnerRoot(ownerRoot: Path): List<SkillInfo> {
        if (!Files.isDirectory(ownerRoot)) {
            logger.debug("Skipping non-existent or non-directory owner root: {}", ownerRoot)
            return emptyList()
        }
        return try {
            Files.list(ownerRoot).use { stream ->
                stream.asSequence()
                    // Dot directories are working state, not skills: the sync service stages restores
                    // as `.restore-*` siblings inside the very root being scanned here.
                    .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
                    .mapNotNull { parseOrNull(it.resolve(SkillPaths.SKILL_FILE_NAME)) }
                    .toList()
            }
        } catch (e: Exception) {
            logger.warn("Failed to scan owner root {}: {}", ownerRoot, e.message)
            emptyList()
        }
    }

    /** Null both for "no skill here" and for a SKILL.md that does not parse — one broken skill must not hide the rest. */
    private fun parseOrNull(skillFile: Path): SkillInfo? {
        if (!Files.isRegularFile(skillFile)) return null
        return try {
            SkillLoader.parse(skillFile)
        } catch (e: Exception) {
            logger.warn("Failed to parse SKILL.md at {}: {}", skillFile, e.message)
            null
        }
    }
}
